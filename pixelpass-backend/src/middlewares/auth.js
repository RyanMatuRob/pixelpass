const jwt = require('jsonwebtoken');

// The API signs console sessions with the shared secret the mobile client already uses.
// Moving to the RS256 key pair in services/tokenService.js is a separate migration.
const getSecret = () => process.env.JWT_SECRET || 'fallback_secret';

const ADMIN_ROLE = 'ADMIN';

function readBearerToken(req) {
    const header = req.get('authorization') || '';
    const [scheme, token] = header.split(' ');
    if (!token || scheme.toLowerCase() !== 'bearer') {
        return null;
    }
    return token.trim();
}

// Rejects anonymous callers and exposes the decoded claims as req.auth.
function requireAuth(req, res, next) {
    const token = readBearerToken(req);
    if (!token) {
        return res.status(401).json({ error: 'Authentication required' });
    }

    try {
        req.auth = jwt.verify(token, getSecret());
        return next();
    } catch {
        return res.status(401).json({ error: 'Invalid or expired token' });
    }
}

// Usage: router.get('/', requireAuth, requireRole('ADMIN'), handler)
function requireRole(...roles) {
    return (req, res, next) => {
        const role = req.auth && req.auth.roleCategory;
        if (!role || !roles.includes(role)) {
            return res.status(403).json({ error: 'Forbidden: administrator access required' });
        }
        return next();
    };
}

// Express accepts an array of handlers, so admin routes mount with one argument.
const requireAdmin = [requireAuth, requireRole(ADMIN_ROLE)];

module.exports = {
    ADMIN_ROLE,
    readBearerToken,
    requireAuth,
    requireAdmin,
    requireRole
};
