const express = require('express');
const mongoose = require('mongoose');
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const User = require('../models/User');
const router = express.Router();

const ROLE_CATEGORIES = ['ADMIN', 'SECURITY', 'STAFF', 'STUDENT', 'VISITOR'];
const DEFAULT_TEMP_PASSWORD = 'password123';

const generateUserId = (roleCategory) => {
    const prefix = {
        ADMIN: 'ADM',
        SECURITY: 'SEC',
        STAFF: 'STF',
        STUDENT: 'STD',
        VISITOR: 'VIS'
    }[roleCategory] || 'USR';
    return `${prefix}-${crypto.randomBytes(3).toString('hex').toUpperCase()}`;
};

// Shared projection: the directory never needs the base64 faces, only whether one exists.
const DIRECTORY_PROJECTION = {
    firstName: 1,
    lastName: 1,
    email: 1,
    roleCategory: 1,
    department: 1,
    createdAt: 1,
    // Documents created before isActive existed count as active.
    isActive: { $ifNull: ['$isActive', true] },
    hasFace: {
        $cond: {
            if: {
                $and: [
                    { $ne: ['$referenceFaceBase64', null] },
                    { $ne: ['$referenceFaceBase64', ''] }
                ]
            },
            then: true,
            else: false
        }
    }
};

// Escapes user input before it reaches a RegExp so a stray "(" cannot break the query.
const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// POST /api/users - Create an individual user
router.post('/', async (req, res) => {
    try {
        const {
            firstName,
            lastName,
            email,
            phoneNumber,
            roleCategory,
            department,
            password,
            referenceFaceBase64
        } = req.body || {};

        if (!firstName || !lastName || !email || !roleCategory) {
            return res.status(400).json({ error: "firstName, lastName, email, and roleCategory are required" });
        }

        const normalizedRole = String(roleCategory).toUpperCase();
        if (!ROLE_CATEGORIES.includes(normalizedRole)) {
            return res.status(400).json({ error: `roleCategory must be one of: ${ROLE_CATEGORIES.join(', ')}` });
        }

        const normalizedEmail = String(email).trim().toLowerCase();
        const existingEmail = await User.findOne({ email: normalizedEmail });
        if (existingEmail) {
            return res.status(409).json({ error: "An account with this email already exists" });
        }

        if (phoneNumber) {
            const normalizedPhone = String(phoneNumber).trim();
            const existingPhone = await User.findOne({ phoneNumber: normalizedPhone });
            if (existingPhone) {
                return res.status(409).json({ error: "An account with this phone number already exists" });
            }
        }

        const plainPassword = password && String(password).trim() ? String(password).trim() : DEFAULT_TEMP_PASSWORD;
        const hashedPassword = await bcrypt.hash(plainPassword, 10);
        const userId = generateUserId(normalizedRole);

        const newUser = await User.create({
            userId,
            firstName: String(firstName).trim(),
            lastName: String(lastName).trim(),
            email: normalizedEmail,
            phoneNumber: phoneNumber ? String(phoneNumber).trim() : undefined,
            roleCategory: normalizedRole,
            department: department ? String(department).trim() : undefined,
            password: hashedPassword,
            referenceFaceBase64: referenceFaceBase64 || undefined,
            isActive: true
        });

        res.status(201).json({
            message: "User created successfully",
            user: {
                _id: newUser._id,
                userId: newUser.userId,
                firstName: newUser.firstName,
                lastName: newUser.lastName,
                email: newUser.email,
                phoneNumber: newUser.phoneNumber,
                roleCategory: newUser.roleCategory,
                department: newUser.department,
                isActive: newUser.isActive !== false,
                hasFace: Boolean(newUser.referenceFaceBase64),
                tempPassword: password ? undefined : plainPassword
            }
        });
    } catch (error) {
        console.error("Error creating user:", error);
        res.status(500).json({ error: "Failed to create user account" });
    }
});

// POST /api/users/batch - Bulk create user accounts from CSV import
router.post('/batch', async (req, res) => {
    try {
        const { users } = req.body || {};

        if (!Array.isArray(users) || users.length === 0) {
            return res.status(400).json({ error: "users must be a non-empty array" });
        }

        const created = [];
        const failed = [];

        for (let i = 0; i < users.length; i++) {
            const entry = users[i] || {};
            const {
                firstName,
                lastName,
                email,
                phoneNumber,
                roleCategory,
                department,
                password,
                referenceFaceBase64
            } = entry;

            if (!firstName || !lastName || !email || !roleCategory) {
                failed.push({ index: i, email: email || 'unknown', reason: "Missing required fields (firstName, lastName, email, roleCategory)" });
                continue;
            }

            const normalizedRole = String(roleCategory).toUpperCase();
            if (!ROLE_CATEGORIES.includes(normalizedRole)) {
                failed.push({ index: i, email, reason: `Invalid roleCategory '${roleCategory}'` });
                continue;
            }

            const normalizedEmail = String(email).trim().toLowerCase();
            const existingEmail = await User.findOne({ email: normalizedEmail });
            if (existingEmail) {
                failed.push({ index: i, email: normalizedEmail, reason: "Duplicate email address" });
                continue;
            }

            try {
                const plainPassword = password && String(password).trim() ? String(password).trim() : DEFAULT_TEMP_PASSWORD;
                const hashedPassword = await bcrypt.hash(plainPassword, 10);
                const userId = generateUserId(normalizedRole);

                const newUser = await User.create({
                    userId,
                    firstName: String(firstName).trim(),
                    lastName: String(lastName).trim(),
                    email: normalizedEmail,
                    phoneNumber: phoneNumber ? String(phoneNumber).trim() : undefined,
                    roleCategory: normalizedRole,
                    department: department ? String(department).trim() : undefined,
                    password: hashedPassword,
                    referenceFaceBase64: referenceFaceBase64 || undefined,
                    isActive: true
                });

                created.push({
                    _id: newUser._id,
                    userId: newUser.userId,
                    firstName: newUser.firstName,
                    lastName: newUser.lastName,
                    email: newUser.email,
                    roleCategory: newUser.roleCategory,
                    department: newUser.department,
                    hasFace: Boolean(newUser.referenceFaceBase64)
                });
            } catch (err) {
                failed.push({ index: i, email: normalizedEmail, reason: err.message || "Failed to create user record" });
            }
        }

        res.status(200).json({
            message: `Batch creation complete: ${created.length} created, ${failed.length} failed`,
            createdCount: created.length,
            failedCount: failed.length,
            created,
            failed
        });
    } catch (error) {
        console.error("Error batch creating users:", error);
        res.status(500).json({ error: "Failed to execute batch user creation" });
    }
});

// GET /api/users - Fetch users for the Admin Directory
// Query: ?search=&roleCategory=SECURITY&status=active|revoked
router.get('/', async (req, res) => {
    try {
        const { search, roleCategory, status } = req.query;
        const match = {};

        if (roleCategory && ROLE_CATEGORIES.includes(roleCategory)) {
            match.roleCategory = roleCategory;
        }

        if (status === 'active') {
            match.isActive = { $ne: false };
        } else if (status === 'revoked') {
            match.isActive = false;
        }

        const term = String(search || '').trim();
        if (term) {
            const pattern = new RegExp(escapeRegExp(term), 'i');
            match.$or = [
                { firstName: pattern },
                { lastName: pattern },
                { email: pattern },
                { userId: pattern }
            ];
        }

        // Aggregation keeps the base64 photos out of Node's memory and the response body.
        const users = await User.aggregate([
            { $match: match },
            { $project: DIRECTORY_PROJECTION },
            { $sort: { lastName: 1, firstName: 1 } }
        ]);

        res.status(200).json(users);
    } catch (error) {
        console.error("Error fetching users:", error);
        res.status(500).json({ error: "Failed to fetch user directory" });
    }
});

// PATCH /api/users/:id/revoke - Revoke (default) or restore access.
// Body: { isActive: false } revoked (default), { isActive: true } reinstated.
router.patch('/:id/revoke', async (req, res) => {
    try {
        const { id } = req.params;

        if (!mongoose.Types.ObjectId.isValid(id)) {
            return res.status(400).json({ error: "Invalid user id" });
        }

        const requested = req.body ? req.body.isActive : undefined;
        if (requested !== undefined && typeof requested !== 'boolean') {
            return res.status(400).json({ error: "isActive must be a boolean" });
        }

        // A bare PATCH is a revocation: that is the emergency action operators need most.
        const isActive = requested === undefined ? false : requested;

        const updatedUser = await User.findByIdAndUpdate(
            id,
            { isActive },
            { returnDocument: 'after' }
        );

        if (!updatedUser) {
            return res.status(404).json({ error: "User not found" });
        }

        res.status(200).json({
            message: isActive ? "Access restored" : "Access revoked",
            user: {
                _id: updatedUser._id,
                firstName: updatedUser.firstName,
                lastName: updatedUser.lastName,
                email: updatedUser.email,
                roleCategory: updatedUser.roleCategory,
                department: updatedUser.department,
                isActive: updatedUser.isActive !== false,
                hasFace: Boolean(updatedUser.referenceFaceBase64)
            }
        });
    } catch (error) {
        console.error("Error updating access state:", error);
        res.status(500).json({ error: "Failed to update access state" });
    }
});

// PUT /api/users/:id/biometric - Upload reference photo
router.put('/:id/biometric', async (req, res) => {
    try {
        const { id } = req.params;
        const { referenceFaceBase64 } = req.body;

        if (!referenceFaceBase64) {
            return res.status(400).json({ error: "Missing photo data" });
        }

        // Update the user's document using their database _id
        const updatedUser = await User.findByIdAndUpdate(
            id, 
            { referenceFaceBase64: referenceFaceBase64 },
            { new: true } // Returns the updated document
        );

        if (!updatedUser) {
            return res.status(404).json({ error: "User not found" });
        }

        res.status(200).json({ message: "Biometric photo updated successfully" });
    } catch (error) {
        console.error("Error updating biometric:", error);
        res.status(500).json({ error: "Failed to upload photo" });
    }
});

module.exports = router;
