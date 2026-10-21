const mongoose = require('mongoose');

const UserSchema = new mongoose.Schema({
    userId: { type: String, required: true, unique: true },
    roleCategory: { 
        type: String, 
        enum: ['STAFF', 'STUDENT', 'VISITOR', 'ADMIN'], 
        required: true 
    }, // Limits privileges
    facialEmbedding: { type: [Number], default: [] }, // Ready for biometric face match
    registeredTerminals: [{ type: String }],
    isActive: { type: Boolean, default: true }
}, { timestamps: true });

module.exports = mongoose.model('User', UserSchema);