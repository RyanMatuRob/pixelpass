    const express = require('express');
    const bcrypt = require('bcryptjs');
    const jwt = require('jsonwebtoken');
    const User = require('../models/User');
    const router = express.Router();

    router.post('/login', async (req, res) => {
        try {
            // identifier can be either an email OR a phone number
            const { identifier, password, keepSignedIn } = req.body;

            if (!identifier || !password) {
                return res.status(400).json({ error: "Please provide credentials" });
            }

            // --- NEW: Phone Number Sanitization ---
            let formattedIdentifier = identifier.trim();

            // 1. If user typed exactly 10 digits starting with '0' (e.g., 0712345678)
            if (formattedIdentifier.startsWith('0') && formattedIdentifier.length === 10) {
                formattedIdentifier = '+254' + formattedIdentifier.substring(1);
            } 
            // 2. If user dropped the '0' completely and typed 9 digits (e.g., 712345678)
            else if ((formattedIdentifier.startsWith('7') || formattedIdentifier.startsWith('1')) && formattedIdentifier.length === 9) {
                formattedIdentifier = '+254' + formattedIdentifier;
            }

            // --- UPDATE: Use formattedIdentifier in the query ---
            const user = await User.findOne({
                $or: [
                    { email: formattedIdentifier }, // Won't harm emails, as they don't match the digit rules
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

            // 4. Apply "Keep me signed in" logic
            const expiresIn = keepSignedIn ? '30d' : '24h';

            const token = jwt.sign(
                { userId: user._id, role: user.role },
                process.env.JWT_SECRET || 'fallback_secret',
                { expiresIn: expiresIn }
            );  

            res.status(200).json({ 
            message: "Login successful",
            token: token, 
            role: user.roleCategory 
            });
        } catch (error) {
            console.error("Login error:", error);
            res.status(500).json({ error: "Server error during login" });
        }
    });

    module.exports = router;