const express = require('express');
const User = require('../models/User');
const router = express.Router();

// GET /api/users - Fetch all users for the Admin Directory
router.get('/', async (req, res) => {
    try {
        // We use an aggregation pipeline to avoid downloading massive Base64 strings
        // into Node.js RAM just to check if they exist.
        const users = await User.aggregate([
            {
                $project: {
                    name: 1,
                    email: 1,
                    role: 1,
                    // Returns true if referenceFaceBase64 exists and is not null/empty
                    hasFace: {
                        $cond: {
                            if: { 
                                $and: [
                                    { $ne: ["$referenceFaceBase64", null] }, 
                                    { $ne: ["$referenceFaceBase64", ""] },
                                    { $type: "$referenceFaceBase64" } // Ensure the field exists at all
                                ] 
                            },
                            then: true,
                            else: false
                        }
                    }
                }
            },
            { $sort: { name: 1 } } // Sort alphabetically by name
        ]);

        res.status(200).json(users);
    } catch (error) {
        console.error("Error fetching users:", error);
        res.status(500).json({ error: "Failed to fetch user directory" });
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