const express = require('express');
const AccessLog = require('../models/AccessLog');
const router = express.Router();

// GET /api/logs - Fetch recent scan history
router.get('/', async (req, res) => {
    try {
        // Sort by timestamp descending (newest first) and limit to 50
        const logs = await AccessLog.find()
            .sort({ timestamp: -1 })
            .limit(50)
            .lean();

        res.status(200).json(logs);
    } catch (error) {
        console.error("Error fetching audit logs:", error);
        res.status(500).json({ error: "Failed to fetch audit logs" });
    }
});

module.exports = router;