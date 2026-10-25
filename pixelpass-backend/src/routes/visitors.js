const express = require('express');
const crypto = require('crypto');
const VisitorInvite = require('../models/VisitorInvite');
const router = express.Router();
const jwt = require('jsonwebtoken');
const User = require('../models/User'); // We need this to create the temporary user
const { requireAdmin } = require('../middlewares/auth');
// POST /api/visitors/invite - Create a new visitor invitation (admin console only)
router.post('/invite', requireAdmin, async (req, res) => {
    try {
        const { visitorName, visitorEmail, validFrom, validUntil } = req.body;

        // Generate a secure 32-character random hex string for the URL
        const uniqueToken = crypto.randomBytes(16).toString('hex');

        const invite = new VisitorInvite({
            visitorName,
            visitorEmail,
            validFrom,
            validUntil,
            uniqueToken
        });

        await invite.save();

        // In production, you would trigger an email via SendGrid/AWS SES here.
        // For now, we return the generated link so the admin can copy it.
        const signupLink = `http://localhost:5173/enroll/${uniqueToken}`;

        res.status(201).json({ 
            message: "Invite generated successfully", 
            link: signupLink 
        });

    } catch (error) {
        console.error("Error generating invite:", error);
        res.status(500).json({ error: "Failed to generate invite" });
    }
});

// POST /api/visitors/enroll/:token - Visitor uploads photo and gets QR code
router.post('/enroll/:token', async (req, res) => {
    try {
        const { token } = req.params;
        const { referenceFaceBase64 } = req.body;

        if (!referenceFaceBase64) {
            return res.status(400).json({ error: "Missing biometric photo" });
        }

        // 1. Validate the Invite Token
        const invite = await VisitorInvite.findOne({ uniqueToken: token });
        if (!invite) {
            return res.status(404).json({ error: "Invalid or expired invite link" });
        }
        if (invite.hasEnrolled) {
            return res.status(400).json({ error: "This invite has already been used" });
        }
        
        const now = new Date();
        if (now > invite.validUntil) {
            return res.status(403).json({ error: "This invite has expired" });
        }

        // 2. Create a Temporary User Profile for the Scanner to recognize
        // The directory needs a first and last name, so split whatever the host typed on the invite.
        const nameParts = String(invite.visitorName || 'Visitor').trim().split(/\s+/);
        const firstName = nameParts[0];
        const lastName = nameParts.slice(1).join(' ') || 'Visitor';

        const tempUser = new User({
            firstName,
            lastName,
            department: 'Visitor Reception',
            email: invite.visitorEmail,
            roleCategory: 'VISITOR', // Added to satisfy Mongoose
            password: 'TEMP_VISITOR_PASSWORD_' + Date.now(), // Added dummy password
            userId: 'VIS-' + crypto.randomBytes(4).toString('hex').toUpperCase(),
            referenceFaceBase64: referenceFaceBase64
        });
        await tempUser.save();

        // 3. Mark the invite as used
        invite.hasEnrolled = true;
        await invite.save();

        // 4. Generate the JWT (This is what goes inside the QR Code)
        const qrPayload = jwt.sign(
            { 
                userId: tempUser._id.toString(), 
                roleCategory: 'Visitor' 
            }, 
            process.env.JWT_SECRET || 'fallback_secret',
            { expiresIn: '24h' } // Restrict token lifetime
        );

        res.status(200).json({ 
            message: "Enrollment successful", 
            qrToken: qrPayload,
            validUntil: invite.validUntil
        });

    } catch (error) {
        console.error("Error during visitor enrollment:", error);
        res.status(500).json({ error: "Enrollment failed" });
    }
});

module.exports = router;