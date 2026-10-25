const express = require('express');
const mongoose = require('mongoose');
const User = require('../models/User');
const router = express.Router();

const ROLE_CATEGORIES = ['ADMIN', 'SECURITY', 'STAFF', 'STUDENT', 'VISITOR'];

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
