const express = require('express');
const AccessLog = require('../models/AccessLog');
const User = require('../models/User');
const router = express.Router();

// "Today" is the server's local calendar day, the same window the operators see.
const startOfToday = () => {
    const now = new Date();
    return new Date(now.getFullYear(), now.getMonth(), now.getDate());
};

// GET /api/admin/metrics - Headline counters for the command center
router.get('/', async (req, res) => {
    try {
        const since = startOfToday();
        const today = { timestamp: { $gte: since } };

        const [
            totalUsers,
            activeUsers,
            enrolledBiometrics,
            scansToday,
            grantedToday,
            blockedToday,
            provisionalToday,
            anomaliesToday,
            totalScans,
            totalAnomalies,
            latestScan
        ] = await Promise.all([
            User.countDocuments(),
            // Documents created before isActive existed count as active.
            User.countDocuments({ isActive: { $ne: false } }),
            User.countDocuments({ referenceFaceBase64: { $exists: true, $nin: [null, ''] } }),
            AccessLog.countDocuments(today),
            // Older rows have no accessDecision, but a biometric pass was always a grant.
            AccessLog.countDocuments({ ...today, biometricOutcome: true }),
            AccessLog.countDocuments({ ...today, biometricOutcome: false }),
            AccessLog.countDocuments({ ...today, accessDecision: 'PROVISIONAL' }),
            AccessLog.countDocuments({ ...today, isAnomaly: true }),
            AccessLog.countDocuments(),
            AccessLog.countDocuments({ isAnomaly: true }),
            AccessLog.findOne().sort({ timestamp: -1 }).select('timestamp').lean()
        ]);

        res.status(200).json({
            totalUsers,
            activeUsers,
            revokedUsers: totalUsers - activeUsers,
            enrolledBiometrics,
            scansToday,
            grantedToday,
            blockedToday,
            provisionalToday,
            anomaliesToday,
            totalScans,
            totalAnomalies,
            lastScanAt: latestScan ? latestScan.timestamp : null,
            generatedAt: new Date()
        });
    } catch (error) {
        console.error("Error building metrics:", error);
        res.status(500).json({ error: "Failed to build metrics" });
    }
});

module.exports = router;
