// Attaches a real face photo to an existing account so the guard-side face check has a
// reference to compare against.
//
// Run with `npm run set:face` from pixelpass-backend:
//   npm run set:face -- --list
//   npm run set:face -- --match student@company.com --photo "C:\Users\me\Pictures\me.jpg"
//
// The photo is stored verbatim as Base64 in `User.referenceFaceBase64`, which is exactly the
// field `POST /api/scan` forwards to the ML service as `reference_face_base64`.
const fs = require('node:fs');
const path = require('node:path');
require('dotenv').config({ path: path.resolve(__dirname, '..', '.env') });

const mongoose = require('mongoose');
const bcrypt = require('bcryptjs');
const User = require('../src/models/User');

// Explicit seed target first, then the URI the API itself uses, then a local development database.
const MONGODB_URI =
    process.env.SEED_MONGO_URI ||
    process.env.MONGO_URI ||
    process.env.MONGODB_URI ||
    'mongodb://localhost:27017/pixelpass_dev';

// DeepFace decodes through Pillow, so anything Pillow understands is accepted. The extension
// only decides which image format the stored bytes are.
const MIME_BY_EXTENSION = {
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.png': 'image/png',
    '.webp': 'image/webp',
    '.bmp': 'image/bmp'
};

// A single MongoDB document caps out at 16 MB; Base64 adds a third on top of the raw bytes.
const MAX_PHOTO_BYTES = 8 * 1024 * 1024;

// A predictable account for demos: the seeded population has random Base64 placeholders in
// `referenceFaceBase64`, so none of them can ever pass a real face check.
const DEMO_ACCOUNT = {
    userId: 'DEMO-0001',
    email: 'demo.student@pixelpass.dev',
    password: process.env.DEMO_PASSWORD || 'password123',
    firstName: 'Demo',
    lastName: 'Student',
    department: 'Computer Science',
    roleCategory: 'STUDENT'
};

const describeTarget = (uri) => uri.replace(/\/\/([^@/]+)@/, '//***@').split('?')[0];

const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const parseArgs = (argv) => {
    const read = (name) => {
        const index = argv.indexOf(`--${name}`);
        return index === -1 ? undefined : argv[index + 1];
    };

    return {
        list: argv.includes('--list'),
        demo: argv.includes('--demo'),
        match: read('match'),
        photo: read('photo'),
        dryRun: argv.includes('--dry-run'),
        quiet: argv.includes('--quiet')
    };
};

const readPhoto = (photoPath) => {
    const resolved = path.resolve(photoPath);

    if (!fs.existsSync(resolved)) {
        throw new Error(`Photo not found: ${resolved}`);
    }

    const stats = fs.statSync(resolved);
    if (!stats.isFile()) {
        throw new Error(`Photo is not a file: ${resolved}`);
    }
    if (stats.size === 0) {
        throw new Error(`Photo is empty: ${resolved}`);
    }
    if (stats.size > MAX_PHOTO_BYTES) {
        const limitMb = Math.round(MAX_PHOTO_BYTES / (1024 * 1024));
        throw new Error(`Photo is ${(stats.size / 1024 / 1024).toFixed(1)} MB; keep it under ${limitMb} MB.`);
    }

    const extension = path.extname(resolved).toLowerCase();
    const mimeType = MIME_BY_EXTENSION[extension];
    if (!mimeType) {
        throw new Error(
            `Unsupported photo type "${extension || '(none)'}". Use one of: ${Object.keys(MIME_BY_EXTENSION).join(', ')}`
        );
    }

    return {
        resolved,
        mimeType,
        bytes: stats.size,
        base64: fs.readFileSync(resolved).toString('base64')
    };
};

const listUsers = async () => {
    // The base64 photos stay out of the projection; only whether one exists matters here.
    const users = await User.aggregate([
        {
            $project: {
                userId: 1,
                email: 1,
                roleCategory: 1,
                firstName: 1,
                lastName: 1,
                isActive: { $ifNull: ['$isActive', true] },
                hasFace: {
                    $cond: {
                        if: {
                            $and: [
                                { $ne: ['$referenceFaceBase64', null] },
                                { $ne: ['$referenceFaceBase64', ''] }
                            ]
                        },
                        then: true,
                        else: false
                    }
                }
            }
        },
        { $sort: { roleCategory: 1, email: 1 } },
        { $limit: 250 }
    ]);

    console.log(`👥 ${users.length} account(s) in ${describeTarget(MONGODB_URI)}:`);
    for (const user of users) {
        const name = [user.firstName, user.lastName].filter(Boolean).join(' ') || '-';
        const state = user.isActive === false ? 'revoked' : 'active';
        console.log(
            `   ${String(user.userId).padEnd(12)} ${String(user.email).padEnd(34)} ` +
            `${String(user.roleCategory).padEnd(9)} ${state.padEnd(8)} face=${user.hasFace ? 'yes' : 'no '}  ${name}`
        );
    }

    const withFace = users.filter((user) => user.hasFace).length;
    console.log(`   with a reference photo: ${withFace}/${users.length}`);
    console.log('   Attach one with: npm run set:face -- --match <email-or-userId> --photo "<path>"');

    return users.length;
};

// Creates the demo account on first use so a demo never depends on remembering a seeded address.
const ensureDemoAccount = async () => {
    const existing = await User.findOne({
        $or: [{ email: DEMO_ACCOUNT.email }, { userId: DEMO_ACCOUNT.userId }]
    })
        .select('userId email firstName lastName roleCategory')
        .lean();

    if (existing) return existing;

    const password = await bcrypt.hash(DEMO_ACCOUNT.password, 10);
    await User.create({
        ...DEMO_ACCOUNT,
        password,
        isActive: true,
        facialEmbedding: []
    });
    console.log(`🆕 Created demo account ${DEMO_ACCOUNT.email} / ${DEMO_ACCOUNT.password}`);

    return User.findOne({ email: DEMO_ACCOUNT.email })
        .select('userId email firstName lastName roleCategory')
        .lean();
};

const attachPhoto = async ({ match, demo, photoPath, dryRun, quiet }) => {
    if (!demo && !match) {
        throw new Error('Missing --match <email-or-userId> (or --demo). Run with --list to see the accounts.');
    }
    if (!photoPath) {
        throw new Error('Missing --photo "<path to a face photo>".');
    }

    const photo = readPhoto(photoPath);

    if (dryRun) {
        const target = demo
            ? `the demo account ${DEMO_ACCOUNT.email}`
            : `the account matching "${match}"`;
        console.log(`🧪 Dry run: would set ${photo.resolved} on ${target}`);
        return;
    }

    let user;
    if (demo) {
        user = await ensureDemoAccount();
    } else {
        // Exact, case-insensitive match so a shortened address cannot silently hit the wrong person.
        const pattern = new RegExp(`^${escapeRegExp(match)}$`, 'i');
        user = await User.findOne({ $or: [{ email: pattern }, { userId: pattern }] })
            .select('userId email firstName lastName roleCategory')
            .lean();
    }

    if (!user) {
        throw new Error(`No account matches "${match}". Run --list to see the available accounts.`);
    }

    const summary =
        `${user.userId} <${user.email}> (${user.roleCategory}) ` +
        `${[user.firstName, user.lastName].filter(Boolean).join(' ') || '-'}`;

    const result = await User.updateOne(
        { _id: user._id },
        { $set: { referenceFaceBase64: photo.base64 } }
    );

    if (result.matchedCount !== 1) {
        throw new Error(`Update matched ${result.matchedCount} accounts; refusing to continue.`);
    }

    const stored = await User.findById(user._id, { referenceFaceBase64: 1 }).lean();
    const storedLength = stored && stored.referenceFaceBase64 ? stored.referenceFaceBase64.length : 0;

    if (!quiet) {
        console.log(`📷 Photo: ${photo.resolved} (${photo.mimeType}, ${(photo.bytes / 1024).toFixed(0)} KB)`);
        console.log(`🔐 Base64 payload: ${photo.base64.length} chars`);
    }
    console.log(`✅ Reference face attached to ${summary}`);
    console.log(`   verified in database: ${storedLength === photo.base64.length ? 'yes' : 'NO'} (${storedLength} chars)`);

    if (demo) {
        console.log(`🔑 Sign in on the phone as ${DEMO_ACCOUNT.email} / ${DEMO_ACCOUNT.password}`);
    }
};

const main = async () => {
    if (process.env.NODE_ENV === 'production') {
        throw new Error('Refusing to modify reference photos because NODE_ENV is production.');
    }

    const options = parseArgs(process.argv.slice(2));

    await mongoose.connect(MONGODB_URI, { serverSelectionTimeoutMS: 10000 });
    console.log(`📦 Connected to MongoDB: ${describeTarget(MONGODB_URI)}`);

    if (options.list || (!options.demo && !options.match && !options.photo)) {
        await listUsers();
        return;
    }

    await attachPhoto({
        match: options.match,
        demo: options.demo,
        photoPath: options.photo,
        dryRun: options.dryRun,
        quiet: options.quiet
    });
};

main()
    .then(async () => {
        await mongoose.disconnect();
        console.log('🔌 Disconnected from MongoDB.');
        process.exit(0);
    })
    .catch(async (error) => {
        console.error('❌ Error updating reference face:', error.message);
        await mongoose.disconnect().catch(() => {});
        console.log('🔌 Disconnected from MongoDB.');
        process.exit(1);
    });
