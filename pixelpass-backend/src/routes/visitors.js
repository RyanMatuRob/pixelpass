const express = require('express');
const crypto = require('crypto');
const mongoose = require('mongoose');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const Invitation = require('../models/Invitation');
const User = require('../models/User');
const { ADMIN_ROLE, requireAuth } = require('../middlewares/auth');

const router = express.Router();

const { INVITATION_STATUS, INVITATION_STATUSES } = Invitation;

// Who may host a visitor. Security officers scan passes; they do not mint them.
const INVITE_ROLES = ['STAFF', 'STUDENT', ADMIN_ROLE];

const DEFAULT_DURATION_HOURS = 8;
const MIN_DURATION_HOURS = 1;
const MAX_DURATION_HOURS = 168; // one week is the longest window a host can request
const MAX_FACE_LENGTH = 8 * 1024 * 1024; // characters of Base64; express.json allows 10 MB
const MAX_GEOHASH_LENGTH = 12;
const VISITOR_ROLE = 'VISITOR';
const VISITOR_DEPARTMENT = 'Visitor Reception';

// A phone number only has to be recognisably dialable; the invite is delivered out of band.
const PHONE_PATTERN = /^\+?[0-9][0-9\s-]{6,19}$/;
const DATA_URL_PREFIX = /^data:image\/[a-z0-9.+-]+;base64,/i;
const BASE64_BODY = /^[A-Za-z0-9+/]+={0,2}$/;

// Fields the console and the mobile invite tab both need, without the biometrics.
const HOST_SELECT = 'firstName lastName roleCategory department';

// The deep link the host shares. The bundled SPA reads the token from `?token=`, and
// deployments point INVITE_BASE_URL at the public visitor portal.
const inviteBaseUrl = () =>
    (process.env.INVITE_BASE_URL || 'http://localhost:5173/enroll').replace(/\/+$/, '');

const buildInviteLink = (token) => `${inviteBaseUrl()}?token=${encodeURIComponent(token)}`;

// Passes are signed with the shared secret the mobile scanner already replays to the
// gateway, so a visitor pass travels the same verification path as a staff pass.
const getSecret = () => process.env.JWT_SECRET || 'fallback_secret';

const newInviteToken = () => crypto.randomBytes(24).toString('base64url');

const hashEmbedding = (value) => crypto.createHash('sha256').update(value).digest('base64url');

const isObjectId = (value) => typeof value === 'string' && mongoose.Types.ObjectId.isValid(value);

const parseDate = (value) => {
    if (value === undefined || value === null || value === '') {
        return null;
    }

    const parsed = new Date(value);
    return Number.isNaN(parsed.getTime()) ? null : parsed;
};

const normalizeText = (value, maxLength) => {
    if (typeof value !== 'string') {
        return null;
    }

    const trimmed = value.trim();
    return trimmed ? trimmed.slice(0, maxLength) : null;
};

const normalizeGeohash = (value) => {
    const candidate = normalizeText(value, MAX_GEOHASH_LENGTH);
    return candidate && /^[0-9a-z]+$/i.test(candidate) ? candidate.toLowerCase() : null;
};

// Visitors upload a selfie from a browser, so the payload arrives as a data URL or as raw
// Base64. Store what they sent (like PUT /api/users/:id/biometric) but refuse nonsense.
const normalizeFace = (value) => {
    const raw = typeof value === 'string' ? value.trim() : '';
    if (!raw) {
        return { error: 'faceEmbedding is required' };
    }
    if (raw.length > MAX_FACE_LENGTH) {
        return { error: 'faceEmbedding is too large' };
    }

    const body = raw.replace(DATA_URL_PREFIX, '').replace(/\s+/g, '');
    if (body.length < 64 || !BASE64_BODY.test(body)) {
        return { error: 'faceEmbedding must be a Base64-encoded image' };
    }

    return { value: raw };
};

// New rows key on `inviteToken`; links minted before this API used `uniqueToken`.
const loadInvitation = (token) =>
    Invitation.findOne({ $or: [{ inviteToken: token }, { uniqueToken: token }] });

const isExpired = (invite, now) => Boolean(invite.validUntil) && invite.validUntil <= now;

const splitVisitorName = (value) => {
    const parts = String(value || '').trim().split(/\s+/).filter(Boolean);
    return {
        firstName: parts[0] || VISITOR_ROLE,
        lastName: parts.slice(1).join(' ') || 'Guest'
    };
};

const hostSummary = (inviter) => {
    if (!inviter || typeof inviter !== 'object' || inviter instanceof mongoose.Types.ObjectId) {
        return null;
    }

    const name = [inviter.firstName, inviter.lastName].filter(Boolean).join(' ').trim();
    return {
        id: inviter._id,
        name: name || 'Campus host',
        roleCategory: inviter.roleCategory || null,
        department: inviter.department || null
    };
};

const inviteSummary = (invite, { includeToken = false } = {}) => {
    const summary = {
        id: invite._id,
        visitorName: invite.visitorName,
        visitorPhone: invite.visitorPhone || null,
        visitorEmail: invite.visitorEmail || null,
        status: invite.currentStatus(),
        validFrom: invite.validFrom,
        validUntil: invite.validUntil,
        acceptedAt: invite.acceptedAt || null,
        revokedAt: invite.revokedAt || null,
        createdAt: invite.createdAt || null,
        host: hostSummary(invite.inviterId)
    };

    // The token is a bearer secret: only the host who minted it gets it back.
    if (includeToken && invite.inviteToken) {
        summary.inviteToken = invite.inviteToken;
        summary.link = buildInviteLink(invite.inviteToken);
    }

    return summary;
};

const visitorSummary = (visitor) => ({
    id: visitor._id,
    userId: visitor.userId,
    firstName: visitor.firstName,
    lastName: visitor.lastName,
    roleCategory: visitor.roleCategory,
    department: visitor.department,
    isActive: visitor.isActive !== false,
    accessValidFrom: visitor.accessValidFrom || null,
    accessValidUntil: visitor.accessValidUntil || null
});

// Only staff, students and admins may host a visitor, and the token must identify them.
const requireHost = (req, res, next) => {
    const role = req.auth && req.auth.roleCategory;
    if (!INVITE_ROLES.includes(role)) {
        return res
            .status(403)
            .json({ error: 'Forbidden: only staff, students or administrators can invite visitors' });
    }
    if (!isObjectId(String((req.auth && req.auth.userId) || ''))) {
        return res.status(401).json({ error: 'Session does not identify a host account' });
    }

    return next();
};

// Two hosts can, in theory, draw the same token; the unique index catches it and we re-roll.
const createInvitation = async (fields) => {
    for (let attempt = 0; attempt < 3; attempt += 1) {
        try {
            const inviteToken = newInviteToken();
            return await Invitation.create({
                ...fields,
                inviteToken,
                // Mirrors the token so pre-lifecycle queries and the collection's older
                // unique index keep working.
                uniqueToken: inviteToken,
                status: INVITATION_STATUS.PENDING
            });
        } catch (error) {
            const tokenCollision =
                error &&
                error.code === 11000 &&
                Boolean(
                    (error.keyValue && error.keyValue.inviteToken) ||
                        (error.keyValue && error.keyValue.uniqueToken)
                );
            if (!tokenCollision) {
                throw error;
            }
        }
    }

    throw new Error('Could not allocate a unique invite token');
};

// POST /api/visitors/invite - a host mints a time-bound link for one visitor.
// Body: { visitorName, visitorPhone, visitDate?, durationHours?, visitorEmail?, validFrom?, validUntil? }
router.post('/invite', requireAuth, requireHost, async (req, res) => {
    try {
        const body = req.body || {};

        const visitorName = normalizeText(body.visitorName, 120);
        if (!visitorName) {
            return res.status(400).json({ error: 'visitorName is required' });
        }

        const visitorPhone = normalizeText(body.visitorPhone, 20);
        const visitorEmail = normalizeText(body.visitorEmail, 254);
        if (!visitorPhone && !visitorEmail) {
            return res.status(400).json({ error: 'visitorPhone or visitorEmail is required' });
        }
        if (visitorPhone && !PHONE_PATTERN.test(visitorPhone)) {
            return res.status(400).json({ error: 'visitorPhone must look like +254712345678' });
        }

        // The console sends an explicit window; the mobile invite tab sends a visit date and
        // a duration, from which the same window is derived.
        const requestedFrom = parseDate(body.validFrom);
        const requestedUntil = parseDate(body.validUntil);
        const visitDate = parseDate(body.visitDate);
        if (body.validFrom && !requestedFrom) {
            return res.status(400).json({ error: 'validFrom must be a valid date' });
        }
        if (body.validUntil && !requestedUntil) {
            return res.status(400).json({ error: 'validUntil must be a valid date' });
        }
        if (body.visitDate && !visitDate) {
            return res.status(400).json({ error: 'visitDate must be a valid date' });
        }

        const durationHours =
            body.durationHours === undefined || body.durationHours === null
                ? DEFAULT_DURATION_HOURS
                : Number(body.durationHours);
        if (
            !Number.isFinite(durationHours) ||
            durationHours < MIN_DURATION_HOURS ||
            durationHours > MAX_DURATION_HOURS
        ) {
            return res.status(400).json({
                error: `durationHours must be between ${MIN_DURATION_HOURS} and ${MAX_DURATION_HOURS}`
            });
        }

        const now = new Date();
        const validFrom = requestedFrom || visitDate || now;
        const validUntil =
            requestedUntil || new Date(validFrom.getTime() + durationHours * 60 * 60 * 1000);

        if (validUntil <= validFrom) {
            return res.status(400).json({ error: 'validUntil must be after validFrom' });
        }
        if (validUntil <= now) {
            return res.status(400).json({ error: 'validUntil must be in the future' });
        }

        const invite = await createInvitation({
            inviterId: req.auth.userId,
            visitorName,
            visitorPhone,
            visitorEmail: visitorEmail ? visitorEmail.toLowerCase() : null,
            validFrom,
            validUntil
        });

        await invite.populate({ path: 'inviterId', select: HOST_SELECT });

        const link = buildInviteLink(invite.inviteToken);

        // Delivery (SMS or email) is a later integration; the host copies the link for now.
        return res.status(201).json({
            message: 'Invite generated successfully',
            link,
            invite: inviteSummary(invite, { includeToken: true })
        });
    } catch (error) {
        console.error('Error generating visitor invite:', error);
        return res.status(500).json({ error: 'Failed to generate invite' });
    }
});

// GET /api/visitors/invite/:token - what the visitor's browser needs before the selfie.
router.get('/invite/:token', async (req, res) => {
    try {
        const token = String(req.params.token || '').trim();
        if (!token) {
            return res.status(400).json({ error: 'Invite token is required' });
        }

        const invite = await loadInvitation(token).populate({ path: 'inviterId', select: HOST_SELECT });
        if (!invite) {
            return res.status(404).json({ error: 'Invite link is invalid' });
        }

        const status = invite.currentStatus();
        if (status === INVITATION_STATUS.REVOKED) {
            return res.status(409).json({ error: 'This invite has been revoked', status });
        }
        if (status === INVITATION_STATUS.ACCEPTED) {
            return res
                .status(409)
                .json({ error: 'This invite has already been used', status, validUntil: invite.validUntil });
        }
        if (isExpired(invite, new Date())) {
            return res
                .status(410)
                .json({ error: 'This invite has expired', status: 'EXPIRED', validUntil: invite.validUntil });
        }

        return res.status(200).json({ invite: inviteSummary(invite) });
    } catch (error) {
        console.error('Error reading visitor invite:', error);
        return res.status(500).json({ error: 'Failed to read invite' });
    }
});

// Acceptance turns the visitor into an ephemeral account. Nothing about it is a login:
// the password is random, the email is a placeholder, and the row dies with the invite.
const createVisitorUser = async ({ invite, faceBase64 }) => {
    const { firstName, lastName } = splitVisitorName(invite.visitorName);
    const password = await bcrypt.hash(crypto.randomBytes(24).toString('hex'), 10);

    for (let attempt = 0; attempt < 3; attempt += 1) {
        try {
            return await User.create({
                userId: `VIS-${crypto.randomBytes(4).toString('hex').toUpperCase()}`,
                firstName,
                lastName,
                department: VISITOR_DEPARTMENT,
                email: `visitor.${crypto.randomBytes(6).toString('hex')}@pixelpass.local`,
                password,
                roleCategory: VISITOR_ROLE,
                referenceFaceBase64: faceBase64,
                isActive: true,
                invitationId: invite._id,
                accessValidFrom: invite.validFrom,
                accessValidUntil: invite.validUntil
            });
        } catch (error) {
            // userId and email are unique indexes; draw a new pair before giving up.
            if (error && error.code === 11000 && attempt < 2) {
                continue;
            }
            throw error;
        }
    }

    throw new Error('Could not allocate a unique visitor account');
};

// The visitor has no app to sign a pass with, so the gateway signs it. The token lives
// exactly as long as the invitation window, and `e` carries a digest of the enrolled face
// rather than the image itself, which could never fit inside a QR code.
const signVisitorPass = ({ visitor, invite, faceHash, geohash }) => {
    const now = Math.floor(Date.now() / 1000);
    const subject = String(visitor._id);
    // Truncating (instead of rounding) keeps `exp` inside the window the gate enforces.
    const expiresAt = Math.max(Math.floor(invite.validUntil.getTime() / 1000), now + 1);

    const claims = {
        userId: subject,
        roleCategory: visitor.roleCategory,
        invitationId: String(invite._id),
        u: subject,
        t: invite.validUntil.getTime(),
        e: faceHash,
        // Explicit `exp` rather than `expiresIn`: the pass must expire with the invite, not
        // one rounding step away from it.
        exp: expiresAt
    };
    if (geohash) {
        claims.l = geohash;
    }

    const token = jwt.sign(claims, getSecret(), { subject });

    return {
        token,
        payload: { ...claims, sub: subject },
        validFrom: invite.validFrom,
        validUntil: invite.validUntil,
        expiresAt: new Date(expiresAt * 1000)
    };
};

// Shared by POST /accept (spec shape) and POST /enroll/:token (links already in the wild).
const acceptInvitation = async (req, res, rawToken) => {
    try {
        const token = typeof rawToken === 'string' ? rawToken.trim() : '';
        if (!token) {
            return res.status(400).json({ error: 'inviteToken is required' });
        }

        const invite = await loadInvitation(token).populate({ path: 'inviterId', select: HOST_SELECT });
        if (!invite) {
            return res.status(404).json({ error: 'Invite link is invalid' });
        }

        const status = invite.currentStatus();
        if (status === INVITATION_STATUS.REVOKED) {
            return res.status(409).json({ error: 'This invite has been revoked', status });
        }
        if (status === INVITATION_STATUS.ACCEPTED) {
            return res.status(409).json({ error: 'This invite has already been used', status });
        }

        const now = new Date();
        if (isExpired(invite, now)) {
            return res.status(410).json({ error: 'This invite has expired', status: 'EXPIRED' });
        }

        const body = req.body || {};
        const face = normalizeFace(body.faceEmbedding !== undefined ? body.faceEmbedding : body.referenceFaceBase64);
        if (face.error) {
            return res.status(400).json({ error: face.error });
        }

        const faceHash = hashEmbedding(face.value);
        const geohash = normalizeGeohash(body.geohash);
        const visitor = await createVisitorUser({ invite, faceBase64: face.value });

        // The invite flips first; a crash before the save leaves an unused account rather
        // than a reusable link.
        invite.status = INVITATION_STATUS.ACCEPTED;
        invite.acceptedAt = now;
        invite.visitorUserId = visitor._id;
        invite.faceEmbeddingHash = faceHash;
        invite.hasEnrolled = true; // keeps pre-lifecycle dashboard queries truthful
        // Invites minted before `inviterId` existed are still valid links, so validation is
        // skipped here; every field this handler writes is checked above.
        await invite.save({ validateBeforeSave: false });

        const pass = signVisitorPass({ visitor, invite, faceHash, geohash });

        return res.status(201).json({
            message: 'Enrollment successful',
            invite: inviteSummary(invite),
            visitor: visitorSummary(visitor),
            pass,
            // Kept for the visitor page that already renders these two fields.
            qrToken: pass.token,
            validUntil: invite.validUntil
        });
    } catch (error) {
        console.error('Error accepting visitor invite:', error);
        return res.status(500).json({ error: 'Enrollment failed' });
    }
};

// POST /api/visitors/accept - Body: { inviteToken, faceEmbedding, geohash? }
router.post('/accept', (req, res) => acceptInvitation(req, res, req.body && req.body.inviteToken));

// POST /api/visitors/enroll/:token - legacy path shared with the existing visitor page.
router.post('/enroll/:token', (req, res) => acceptInvitation(req, res, req.params.token));


// PATCH /api/visitors/invite/:id/revoke - the original host or any administrator.
router.patch('/invite/:id/revoke', requireAuth, async (req, res) => {
    try {
        const { id } = req.params;
        if (!isObjectId(id)) {
            return res.status(400).json({ error: 'Invalid invite id' });
        }

        const invite = await Invitation.findById(id).populate({ path: 'inviterId', select: HOST_SELECT });
        if (!invite) {
            return res.status(404).json({ error: 'Invite not found' });
        }

        const callerId = String((req.auth && req.auth.userId) || '');
        const isAdmin = req.auth && req.auth.roleCategory === ADMIN_ROLE;
        const hostId = invite.inviterId && (invite.inviterId._id || invite.inviterId);
        const isHost = Boolean(hostId) && String(hostId) === callerId;

        if (!isAdmin && !isHost) {
            return res
                .status(403)
                .json({ error: 'Forbidden: only the host or an administrator can revoke this invite' });
        }

        if (invite.currentStatus() === INVITATION_STATUS.REVOKED) {
            return res.status(200).json({ message: 'Invite already revoked', invite: inviteSummary(invite) });
        }

        const now = new Date();
        invite.status = INVITATION_STATUS.REVOKED;
        invite.revokedAt = now;
        invite.revokedBy = isObjectId(callerId) ? callerId : null;
        // Legacy rows have no inviterId, so a full validation pass would refuse the save.
        await invite.save({ validateBeforeSave: false });

        // A revoked invite must also disable the ephemeral account it created, otherwise the
        // pass would stay valid until its own expiry.
        const visitorFilter = { $or: [{ invitationId: invite._id }] };
        if (invite.visitorUserId) {
            visitorFilter.$or.push({ _id: invite.visitorUserId });
        }
        const revoked = await User.updateMany(visitorFilter, { isActive: false });

        return res.status(200).json({
            message: 'Invite revoked',
            invite: inviteSummary(invite),
            revokedVisitors: revoked.modifiedCount || 0
        });
    } catch (error) {
        console.error('Error revoking visitor invite:', error);
        return res.status(500).json({ error: 'Failed to revoke invite' });
    }
});

// GET /api/visitors/my-invites - the mobile "Invite" tab's paginated history.
// Query: ?page=1&limit=20&status=PENDING|ACCEPTED|REVOKED
router.get('/my-invites', requireAuth, async (req, res) => {
    try {
        const hostId = String((req.auth && req.auth.userId) || '');
        if (!isObjectId(hostId)) {
            return res.status(401).json({ error: 'Session does not identify a host account' });
        }

        const requestedPage = Number.parseInt(req.query.page, 10);
        const requestedLimit = Number.parseInt(req.query.limit, 10);
        const page = Number.isFinite(requestedPage) && requestedPage > 0 ? requestedPage : 1;
        const limit = Number.isFinite(requestedLimit)
            ? Math.min(Math.max(requestedLimit, 1), 100)
            : 20;

        const match = { inviterId: new mongoose.Types.ObjectId(hostId) };
        const status = String(req.query.status || '').toUpperCase();
        if (status) {
            if (!INVITATION_STATUSES.includes(status)) {
                return res.status(400).json({ error: 'status must be PENDING, ACCEPTED or REVOKED' });
            }
            match.status = status;
        }

        const [total, invites] = await Promise.all([
            Invitation.countDocuments(match),
            Invitation.find(match)
                .sort({ createdAt: -1 })
                .skip((page - 1) * limit)
                .limit(limit)
                .populate({ path: 'inviterId', select: HOST_SELECT })
        ]);

        return res.status(200).json({
            items: invites.map((invite) => inviteSummary(invite, { includeToken: true })),
            page,
            limit,
            total,
            totalPages: Math.max(1, Math.ceil(total / limit)),
            generatedAt: new Date()
        });
    } catch (error) {
        console.error('Error listing visitor invites:', error);
        return res.status(500).json({ error: 'Failed to list invites' });
    }
});

module.exports = router;

