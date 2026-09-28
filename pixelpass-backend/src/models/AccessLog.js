const mongoose = require('mongoose');

const AccessLogSchema = new mongoose.Schema({
    userId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', required: true },
    timestamp: { type: Date, default: Date.now },
    terminalCoordinate: {
        latitude: { type: Number, required: true },
        longitude: { type: Number, required: true }
    }, // Required for calculating physical velocity metrics
    deviceFingerprint: { type: String, required: true },
    biometricOutcome: { type: Boolean, required: true }, // Result of the on-device face match[cite: 6]
    scanIntervalSeconds: { type: Number },
    anomalyScore: { type: Number, default: 0 }, // Populated later by the ML microservice[cite: 6]
}, { timestamps: true });

module.exports = mongoose.model('AccessLog', AccessLogSchema);