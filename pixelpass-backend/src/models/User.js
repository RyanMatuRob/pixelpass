const mongoose = require('mongoose');

const UserSchema = new mongoose.Schema({
    userId: { type: String, required: true, unique: true },
    firstName: { type: String, required: true, trim: true }, // Human-readable name for the admin directory
    lastName: { type: String, required: true, trim: true },  // Searchable in the admin directory
    department: { type: String, trim: true },               // Optional grouping/filter for staff and students
    email: { type: String, required: true, unique: true }, // Added for Sign-In
    password: { type: String, required: true },            // Added for Sign-In
    phoneNumber: { type: String, unique: true, sparse: true },
    roleCategory: { 
        type: String, 
        enum: ['STAFF', 'STUDENT', 'VISITOR', 'ADMIN', 'SECURITY'], 
        required: true 
    }, // Limits privileges based on the proposal specifications
    facialEmbedding: { type: [Number], default: [] }, // Biometric placeholder[cite: 6]
    registeredTerminals: [{ type: String }],
    isActive: { type: Boolean, default: true },
    referenceFaceBase64: { type: String, required: false },
    // Visitor lifecycle: an accepted invitation creates an ephemeral VISITOR account that
    // only exists for the invitation's window. The scan gateway refuses it outside
    // [accessValidFrom, accessValidUntil] and as soon as the host or an admin revokes it.
    invitationId: { type: mongoose.Schema.Types.ObjectId, ref: 'Invitation', default: null },
    accessValidFrom: { type: Date, default: null },
    accessValidUntil: { type: Date, default: null }
}, { timestamps: true });

// Revocation and expiry sweeps look ephemeral accounts up by invite.
UserSchema.index({ invitationId: 1 }, { sparse: true });

module.exports = mongoose.model('User', UserSchema);