const mongoose = require('mongoose');

// One visitor invitation, from the moment a host mints the link until the visit it grants
// is over. Acceptance turns the invitation into an ephemeral User and a gateway-signed pass.
const INVITATION_STATUS = {
    PENDING: 'PENDING',
    ACCEPTED: 'ACCEPTED',
    REVOKED: 'REVOKED'
};

const INVITATION_STATUSES = Object.values(INVITATION_STATUS);

const InvitationSchema = new mongoose.Schema({
    inviterId: {
        type: mongoose.Schema.Types.ObjectId,
        ref: 'User',
        required: true,
        index: true
    },
    visitorName: { type: String, required: true, trim: true },
    // Contact details are optional in the schema because the earlier console captured only
    // an email; the API insists on at least one of them when a host mints a new invite.
    visitorPhone: { type: String, trim: true, default: null },
    visitorEmail: { type: String, trim: true, lowercase: true, default: null },
    // The window the visitor pass is honoured for. The link itself stays usable while the
    // invite is PENDING so the visitor can enrol before the window opens.
    validFrom: { type: Date, required: true },
    validUntil: { type: Date, required: true },
    inviteToken: { type: String, unique: true, sparse: true },
    status: {
        type: String,
        enum: INVITATION_STATUSES,
        default: INVITATION_STATUS.PENDING,
        index: true
    },
    acceptedAt: { type: Date, default: null },
    visitorUserId: { type: mongoose.Schema.Types.ObjectId, ref: 'User', default: null },
    // SHA-256 digest of the enrolled face, echoed on the pass so a terminal can tell which
    // enrolment the QR was minted from without shipping the image inside the code.
    faceEmbeddingHash: { type: String, default: null },
    revokedAt: { type: Date, default: null },
    revokedBy: { type: mongoose.Schema.Types.ObjectId, ref: 'User', default: null },

    // --- Legacy compatibility -------------------------------------------------------
    // Invites minted before this lifecycle existed live in the same collection with
    // `uniqueToken`/`hasEnrolled` and no status, so old links keep working and the admin
    // console keeps its history without a migration. New rows mirror their token here so
    // the collection's older non-sparse unique index stays satisfied.
    uniqueToken: { type: String, default: null },
    hasEnrolled: { type: Boolean, default: false }
}, { timestamps: true, collection: 'visitorinvites' });

// The host dashboard lists one host's invites newest-first; admins sweep by status and expiry.
InvitationSchema.index({ inviterId: 1, createdAt: -1 });
InvitationSchema.index({ status: 1, validUntil: 1 });

// Single reader for the lifecycle state: legacy rows only carry `hasEnrolled`.
InvitationSchema.methods.currentStatus = function currentStatus() {
    if (this.status) {
        return this.status;
    }

    return this.hasEnrolled ? INVITATION_STATUS.ACCEPTED : INVITATION_STATUS.PENDING;
};

module.exports = mongoose.model('Invitation', InvitationSchema);
module.exports.INVITATION_STATUS = INVITATION_STATUS;
module.exports.INVITATION_STATUSES = INVITATION_STATUSES;
