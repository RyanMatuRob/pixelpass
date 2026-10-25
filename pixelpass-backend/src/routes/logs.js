const express = require('express');
const AccessLog = require('../models/AccessLog');
const { ACCESS_DECISIONS } = require('../utils/telemetry');
const router = express.Router();

const DEFAULT_LIMIT = 50;
const ANOMALY_LIMIT = 20;
const MAX_LIMIT = 200;

const parseLimit = (value, fallback = DEFAULT_LIMIT) => {
    const parsed = Number.parseInt(value, 10);
    if (!Number.isFinite(parsed) || parsed <= 0) return fallback;
    return Math.min(parsed, MAX_LIMIT);
};

// Accepts true/false, 1/0 and yes/no. Returns undefined when absent and null when invalid.
const parseBoolean = (value) => {
    if (value === undefined || value === '') return undefined;
    const normalised = String(value).toLowerCase();
    if (['true', '1', 'yes'].includes(normalised)) return true;
    if (['false', '0', 'no'].includes(normalised)) return false;
    return null;
};

// GET /api/logs - Chronological scan telemetry, newest first
// Query: ?limit=50&decision=GRANTED|DENIED|PROVISIONAL&isAnomaly=true|false&userId=<id>
// Only scans recorded after the telemetry phase carry an accessDecision; older rows are
// still returned unless a decision filter is applied.
router.get('/', async (req, res) => {
    try {
        const { decision, isAnomaly, userId } = req.query;
        const filter = {};

        if (decision) {
            if (!ACCESS_DECISIONS.includes(decision)) {
                return res.status(400).json({
                    error: `decision must be one of ${ACCESS_DECISIONS.join(', ')}`
                });
            }
            filter.accessDecision = decision;
        }

        const anomaly = parseBoolean(isAnomaly);
        if (anomaly === null) {
            return res.status(400).json({ error: 'isAnomaly must be true or false' });
        }
        if (anomaly !== undefined) filter.isAnomaly = anomaly;

        if (userId) filter.userId = String(userId);

        const logs = await AccessLog.find(filter)
            .sort({ timestamp: -1 })
            .limit(parseLimit(req.query.limit))
            .lean();

        res.status(200).json(logs);
    } catch (error) {
        console.error("Error fetching audit logs:", error);
        res.status(500).json({ error: "Failed to fetch audit logs" });
    }
});

// GET /api/logs/anomalies - Only the scans the Isolation Forest flagged, newest first
router.get('/anomalies', async (req, res) => {
    try {
        const logs = await AccessLog.find({ isAnomaly: true })
            .sort({ timestamp: -1 })
            .limit(parseLimit(req.query.limit, ANOMALY_LIMIT))
            .lean();

        res.status(200).json(logs);
    } catch (error) {
        console.error("Error fetching anomaly feed:", error);
        res.status(500).json({ error: "Failed to fetch anomaly feed" });
    }
});

module.exports = router;