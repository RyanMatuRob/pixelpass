const express = require('express');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const User = require('../models/User');
const { ADMIN_ROLE } = require('../middlewares/auth');
const router = express.Router();

const getSecret = () => process.env.JWT_SECRET || 'fallback_secret';

// Accepts an email or a Kenyan phone number and returns the form stored on the user.
const normalizeIdentifier = (identifier) => {
    const formatted = String(identifier).trim();

    // 1. If user typed exactly 10 digits starting with '0' (e.g., 0712345678)
    if (formatted.startsWith('0') && formatted.length === 10) {
        return '+254' + formatted.substring(1);
    }
    // 2. If user dropped the '0' completely and typed 9 digits (e.g., 712345678)
    if ((formatted.startsWith('7') || formatted.startsWith('1')) && formatted.length === 9) {
        return '+254' + formatted;
    }
    return formatted;
};

router.post('/login', async (req, res) => {
    try {
        // identifier can be either an email OR a phone number
        const { identifier, password, keepSignedIn, requireAdmin } = req.body;

        if (!identifier || !password) {
            return res.status(400).json({ error: "Please provide credentials" });
        }

        const formattedIdentifier = normalizeIdentifier(identifier);

        const user = await User.findOne({
            $or: [
                { email: formattedIdentifier }, // Emails never match the digit rules above
                { phoneNumber: formattedIdentifier }
            ]
        });

        if (!user) {
            return res.status(401).json({ error: "Invalid credentials" });
        }

        // 3. Verify Password
        const isMatch = await bcrypt.compare(password, user.password);
        if (!isMatch) {
            return res.status(401).json({ error: "Invalid credentials" });
        }

        // 4. A revoked account must not receive a fresh session, even with valid credentials.
        if (user.isActive === false) {
            return res.status(403).json({ error: "This account has been revoked. Contact an administrator." });
        }

        // 5. The operations console asks for an admin-only session; staff and students stay on mobile.
        if (requireAdmin && user.roleCategory !== ADMIN_ROLE) {
            return res.status(403).json({ error: "Forbidden: administrator access required" });
        }

        // 6. Apply "Keep me signed in" logic
        const expiresIn = keepSignedIn ? '30d' : '24h';

        // roleCategory (rather than a non-existent role field) is what the scan route and the
        // admin middleware read back out of the token.
        const token = jwt.sign(
            { userId: user._id, roleCategory: user.roleCategory },
            getSecret(),
            { expiresIn: expiresIn }
        );

        res.status(200).json({
            message: "Login successful",
            token: token,
            role: user.roleCategory, // Kept for the mobile app's LoginResponse shape
            user: {
                id: user._id,
                email: user.email,
                firstName: user.firstName,
                lastName: user.lastName,
                roleCategory: user.roleCategory
            }
        });
    } catch (error) {
        console.error("Login error:", error);
        res.status(500).json({ error: "Server error during login" });
    }
});

module.exports = router;
