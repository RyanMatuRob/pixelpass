const express = require('express');
const jwt = require('jsonwebtoken');
const AccessLog = require('../models/AccessLog');
const User = require('../models/User'); // Import the User model
const router = express.Router();
const mongoose = require('mongoose');
const ML_SERVICE_URL = 'http://127.0.0.1:8000/predict';

router.post('/', async (req, res) => {
    try {
        // The mobile app now sends faceImageBase64 instead of scores
        const { 
            token, 
            faceImageBase64, 
            terminalCoordinate, 
            deviceFingerprint 
        } = req.body;

        // 1. Decrypt JWT
        const decoded = jwt.verify(token, process.env.JWT_SECRET || 'fallback_secret');
        const { userId, roleCategory } = decoded;

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

        // 3. Extract ML Temporal Features
        const now = new Date();
        const hour_of_day = now.getHours();
        const jsDay = now.getDay();
        const day_of_week = jsDay === 0 ? 6 : jsDay - 1; 

        // 4. Calculate Velocity
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

        // 5. Query the Python FastAPI Microservice
        const mlPayload = {
            roleCategory,
            hour_of_day,
            day_of_week,
            scan_frequency_1h,
            live_face_base64: faceImageBase64,
            reference_face_base64: user.referenceFaceBase64
        };

        const mlResponse = await fetch(ML_SERVICE_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(mlPayload)
        });

        if (!mlResponse.ok) throw new Error('ML Microservice unreachable');
        const mlData = await mlResponse.json(); 

        // 6. Save the transaction to MongoDB
        const log = new AccessLog({
            userId,
            roleCategory,
            terminalCoordinate,
            deviceFingerprint,
            biometricConfidence: mlData.biometric_confidence,
            biometricOutcome: mlData.biometric_match,
            scanIntervalSeconds,
            isAnomaly: mlData.is_anomaly,
            anomalyScore: mlData.anomaly_score
        });
        await log.save();

        // 7. Return the final decision to the Security Guard's device
        if (!mlData.biometric_match) {
            return res.status(403).json({
                access: 'DENIED',
                reason: 'Facial Recognition Failed (Identity Mismatch)',
                score: mlData.anomaly_score
            });
        }

        // If biometric match succeeds, grant entry (attaching anomaly flag for telemetry/logging)
        return res.status(200).json({
            access: 'GRANTED',
            message: 'Clear for entry',
            isAnomaly: mlData.is_anomaly,
            score: mlData.anomaly_score
        });

    } catch (error) {
        console.error("Scan processing error:", error.message);
        return res.status(401).json({ access: 'DENIED', reason: 'Invalid or Expired Token' });
    }
});

module.exports = router;
