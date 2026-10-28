const express = require('express');
const jwt = require('jsonwebtoken');
const AccessLog = require('../models/AccessLog');
const User = require('../models/User'); // Import the User model
const { describeAnomaly, decideAccess } = require('../utils/telemetry');
const router = express.Router();

const mongoose = require('mongoose');
// The ML service runs as its own App Service in production, so the endpoint is configurable.
const ML_SERVICE_URL = process.env.ML_SERVICE_URL || 'http://127.0.0.1:8000';
const ML_PREDICT_URL = `${ML_SERVICE_URL.replace(/\/+$/, '')}/predict`;

// Revoked accounts and visitors outside their invitation window are refused before the ML
// service is called: the decision needs no face comparison.
const accountDenialReason = (user, now) => {
    if (user.isActive === false) {
        return 'Account access has been revoked.';
    }
    if (user.roleCategory !== 'VISITOR') {
        return null;
    }
    if (user.accessValidFrom && now < user.accessValidFrom) {
        return 'Visitor pass is not active yet.';
    }
    if (user.accessValidUntil && now > user.accessValidUntil) {
        return 'Visitor pass has expired.';
    }

    return null;
};

// The console should still see refused attempts, so they land in the audit feed with no
// biometric score of their own.
const recordDenial = async ({ req, userId, roleCategory, reason }) => {
    const body = req.body || {};
    const coordinate = body.terminalCoordinate || {};

    try {
        await AccessLog.create({
            userId: String(userId),
            roleCategory: String(roleCategory || 'VISITOR'),
            terminalCoordinate: {
                latitude: Number(coordinate.latitude) || 0,
                longitude: Number(coordinate.longitude) || 0
            },
            deviceFingerprint: body.deviceFingerprint || 'unknown-device',
            biometricConfidence: 0,
            biometricOutcome: false,
            isAnomaly: false,
            anomalyScore: 0,
            anomalyReason: reason,
            accessDecision: 'DENIED'
        });
    } catch (error) {
        console.error('Could not record denied scan:', error.message);
    }
};

router.post('/', async (req, res) => {
    try {
        // The mobile app sends JWT token, optional nonce, face image and device telemetry
        const { 
            token, 
            nonce,
            faceImageBase64,
            terminalCoordinate, 
            deviceFingerprint 
        } = req.body;

        // 1. Decrypt JWT
        const decoded = jwt.verify(token, process.env.JWT_SECRET || 'fallback_secret');
        const { userId, roleCategory } = decoded;

        // 1b. Check if pass nonce has already been consumed (Replay Defence across all terminals)
        if (nonce && typeof nonce === 'string' && nonce.trim().length > 0) {
            const replayedScan = await AccessLog.findOne({ nonce: nonce.trim() });
            if (replayedScan) {
                const refusal = 'This pass has already been used (Replay Attempt)';
                await recordDenial({ req, userId, roleCategory, reason: refusal });
                return res.status(403).json({
                    access: 'DENIED',
                    decision: 'DENIED',
                    reason: refusal,
                    anomalyReason: refusal
                });
            }
        }

        // 2. Fetch the Trusted Reference Photo from the Database
        let user;
        if (mongoose.Types.ObjectId.isValid(userId)) {
            user = await User.findById(userId);
        } else {
            // If the JWT contains a custom string like "STF-001", search your custom fields.
            user = await User.findOne({
                $or: [ { userId: userId }, { employeeId: userId }, { email: userId } ] 
            });
        }
        if (!user || !user.referenceFaceBase64) {
            return res.status(404).json({
                access: 'DENIED',
                reason: 'No biometric reference photo found on file.'
            });
        }

        // 3. Refuse revoked accounts and visitors outside their invitation window before the
        // model is consulted; the attempt is still recorded for the console.
        const now = new Date();
        const denial = accountDenialReason(user, now);
        if (denial) {
            await recordDenial({ req, userId, roleCategory: roleCategory || user.roleCategory, reason: denial });

            return res.status(403).json({
                access: 'DENIED',
                decision: 'DENIED',
                reason: denial,
                anomalyReason: denial
            });
        }

        // 4. Extract ML Temporal Features
        const hour_of_day = now.getHours();
        const jsDay = now.getDay();
        const day_of_week = jsDay === 0 ? 6 : jsDay - 1; 

        // 5. Calculate Velocity
        const oneHourAgo = new Date(now.getTime() - 60 * 60 * 1000);
        
        const lastScan = await AccessLog.findOne({ userId }).sort({ timestamp: -1 });
        const scanIntervalSeconds = lastScan 
            ? Math.floor((now - lastScan.timestamp) / 1000) 
            : null;

        const pastScans = await AccessLog.countDocuments({
            userId,
            timestamp: { $gte: oneHourAgo }
        });
        const scan_frequency_1h = pastScans + 1; 

        // 6. Query the Python FastAPI Microservice
        const mlPayload = {
            roleCategory,
            hour_of_day,
            day_of_week,
            scan_frequency_1h,
            live_face_base64: faceImageBase64,
            reference_face_base64: user.referenceFaceBase64
        };

        const mlResponse = await fetch(ML_PREDICT_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(mlPayload)
        });

        if (!mlResponse.ok) throw new Error('ML Microservice unreachable');
        const mlData = await mlResponse.json(); 

        // 7. Explain the model's verdict and record the operator-facing decision
        const accessDecision = decideAccess({
            biometricMatch: mlData.biometric_match,
            isAnomaly: mlData.is_anomaly
        });
        const anomalyReason = describeAnomaly({
            isAnomaly: mlData.is_anomaly,
            previousLog: lastScan,
            terminalCoordinate,
            scanIntervalSeconds,
            scanFrequencyOneHour: scan_frequency_1h,
            hourOfDay: hour_of_day
        });

        // 8. Save the transaction to MongoDB
        const log = new AccessLog({
            userId,
            roleCategory,
            nonce: nonce && typeof nonce === 'string' ? nonce.trim() : undefined,
            terminalCoordinate,
            deviceFingerprint,
            biometricConfidence: mlData.biometric_confidence,
            biometricOutcome: mlData.biometric_match,
            scanIntervalSeconds,
            isAnomaly: mlData.is_anomaly,
            anomalyScore: mlData.anomaly_score,
            anomalyReason,
            accessDecision
        });
        await log.save();

        // 9. Return the final decision to the Security Guard's device. `access` keeps the
        // GRANTED/DENIED wording the mobile app already understands; `decision` adds the
        // provisional label and `anomalyReason` explains any ML flag to the console.
        if (!mlData.biometric_match) {
            return res.status(403).json({
                access: 'DENIED',
                decision: accessDecision,
                reason: 'Facial Recognition Failed (Identity Mismatch)',
                anomalyReason,
                score: mlData.anomaly_score
            });
        }

        // If biometric match succeeds, grant entry (attaching anomaly flag for telemetry/logging)
        return res.status(200).json({
            access: 'GRANTED',
            decision: accessDecision,
            message: 'Clear for entry',
            isAnomaly: mlData.is_anomaly,
            anomalyReason,
            score: mlData.anomaly_score
        });

    } catch (error) {
        console.error("Scan processing error:", error.message);
        return res.status(401).json({ access: 'DENIED', reason: 'Invalid or Expired Token' });
    }
});

module.exports = router;
