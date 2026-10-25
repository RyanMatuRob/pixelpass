const mongoose = require('mongoose');

const AccessLogSchema = new mongoose.Schema({
    // Using String to accommodate IDs like "STF-001", or change to ObjectId if using Mongo's default _id
    userId: { type: String, required: true }, 
    roleCategory: { type: String, required: true }, // Required for ML context
    timestamp: { type: Date, default: Date.now },
    terminalCoordinate: {
        latitude: { type: Number, required: true },
        longitude: { type: Number, required: true }
    }, // Required for calculating physical velocity metrics
    deviceFingerprint: { type: String, required: true },
    biometricConfidence: { type: Number, required: true }, // The 0-100 float for the ML model
    biometricOutcome: { type: Boolean, required: true }, // Result of the on-device face match
    scanIntervalSeconds: { type: Number },
    isAnomaly: { type: Boolean, required: true }, // ML prediction verdict
    anomalyScore: { type: Number, default: 0 }, // Populated by the ML microservice
    // Human-readable explanation built by src/utils/telemetry.js for flagged scans.
    anomalyReason: { type: String, default: null },
    // Operator-facing verdict: DENIED is a biometric mismatch, PROVISIONAL is a match
    // the model flagged, GRANTED is a clean pass. Older rows have no value stored.
    accessDecision: {
        type: String,
        enum: ['GRANTED', 'DENIED', 'PROVISIONAL'],
        default: 'GRANTED',
        index: true
    },
}, { timestamps: true });

// The telemetry feed reads newest-first, and the anomaly panel reads flagged rows the same way.
AccessLogSchema.index({ timestamp: -1 });
AccessLogSchema.index({ isAnomaly: 1, timestamp: -1 });


module.exports = mongoose.model('AccessLog', AccessLogSchema);