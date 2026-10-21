const mongoose = require('mongoose');

const visitorInviteSchema = new mongoose.Schema({
    visitorName: { type: String, required: true },
    visitorEmail: { type: String, required: true },
    validFrom: { type: Date, required: true },
    validUntil: { type: Date, required: true },
    // uniqueToken is the secure random string we put in the URL link
    uniqueToken: { type: String, required: true, unique: true }, 
    // hasEnrolled tracks if they clicked the link and uploaded their selfie yet
    hasEnrolled: { type: Boolean, default: false },
    createdAt: { type: Date, default: Date.now }
});

module.exports = mongoose.model('VisitorInvite', visitorInviteSchema);