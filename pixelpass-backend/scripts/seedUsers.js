// Seeds a realistic, re-runnable user population for local and staging testing.
// Run with `npm run seed:users` from pixelpass-backend. Never point it at production.
const path = require('node:path');
require('dotenv').config({ path: path.resolve(__dirname, '..', '.env') });

const mongoose = require('mongoose');
const bcrypt = require('bcryptjs');
const { faker } = require('@faker-js/faker');
const User = require('../src/models/User');

// Explicit seed target first, then the URI the API itself uses, then a local development database.
const MONGODB_URI =
    process.env.SEED_MONGO_URI ||
    process.env.MONGO_URI ||
    process.env.MONGODB_URI ||
    'mongodb://localhost:27017/pixelpass_dev';

// Development-only credential shared by every seeded account.
const SEED_PASSWORD = process.env.SEED_PASSWORD || 'password123';

// Role mix tuned for the admin dashboard: a student majority with a thin operations layer.
// Values must stay inside the User.roleCategory enum.
const ROLE_DISTRIBUTION = [
    { roleCategory: 'ADMIN', count: 3 },
    { roleCategory: 'SECURITY', count: 10 },
    { roleCategory: 'STAFF', count: 40 },
    { roleCategory: 'STUDENT', count: 100 },
    { roleCategory: 'VISITOR', count: 20 }
];

const EXPECTED_USERS = ROLE_DISTRIBUTION.reduce((total, group) => total + group.count, 0);

const describeTarget = (uri) => uri.replace(/\/\/([^@/]+)@/, '//***@').split('?')[0];

// userId, email and phoneNumber are unique indexes, so one random collision aborts the whole batch.
const uniqueValue = (seen, generate) => {
    let value = generate();
    while (seen.has(value)) {
        value = generate();
    }
    seen.add(value);
    return value;
};

const buildUsers = async () => {
    const password = await bcrypt.hash(SEED_PASSWORD, 10);
    const seen = { userId: new Set(), email: new Set(), phoneNumber: new Set() };
    const users = [];

    for (const group of ROLE_DISTRIBUTION) {
        for (let index = 0; index < group.count; index += 1) {
            const firstName = faker.person.firstName();
            const lastName = faker.person.lastName();

            // Stand-in for a captured face: random bytes kept both as the stored Base64 reference
            // and as the numeric vector the ML service expects.
            const referenceFaceBase64 = Buffer.from(faker.string.alphanumeric(128)).toString('base64');
            const facialEmbedding = Array.from(Buffer.from(referenceFaceBase64, 'base64'));

            const createdAt = faker.date.past({ years: 1 });

            users.push({
                userId: uniqueValue(seen.userId, () => `usr_${faker.string.numeric(5)}`),
                email: uniqueValue(seen.email, () =>
                    faker.internet.email({ firstName, lastName, provider: 'pixelpass.dev' }).toLowerCase()
                ),
                password,
                phoneNumber: uniqueValue(seen.phoneNumber, () => faker.phone.number()),
                roleCategory: group.roleCategory,
                // 5% of regular accounts are revoked; visitors stay active so they can still enrol.
                isActive: group.roleCategory !== 'VISITOR'
                    ? faker.datatype.boolean({ probability: 0.95 })
                    : true,
                referenceFaceBase64,
                facialEmbedding,
                createdAt,
                updatedAt: createdAt
            });
        }
    }

    return users;
};

const seedDatabase = async () => {
    if (process.env.NODE_ENV === 'production') {
        throw new Error('Refusing to seed because NODE_ENV is production.');
    }

    await mongoose.connect(MONGODB_URI, { serverSelectionTimeoutMS: 10000 });
    console.log(`📦 Connected to MongoDB: ${describeTarget(MONGODB_URI)}`);

    const usersToInsert = await buildUsers();

    // Re-runnable: a second run must not double the population or trip a unique index.
    const cleared = await User.deleteMany({});
    console.log(`🧹 Cleared ${cleared.deletedCount} existing users.`);

    // Timestamps are written by hand so createdAt spreads over the past year for chart-friendly data.
    const inserted = await User.insertMany(usersToInsert, { timestamps: false });
    console.log(`✅ Successfully seeded ${inserted.length} users (expected ${EXPECTED_USERS}) across all categories.`);

    const byRole = await User.aggregate([
        { $group: { _id: '$roleCategory', count: { $sum: 1 } } },
        { $sort: { _id: 1 } }
    ]);
    for (const group of byRole) {
        console.log(`   ${String(group._id).padEnd(9)} ${group.count}`);
    }

    const revoked = await User.countDocuments({ isActive: false });
    const total = await User.countDocuments();
    const sampleAdmin = await User.findOne({ roleCategory: 'ADMIN' }).lean();

    console.log(`   revoked   ${revoked}`);
    console.log(`   total     ${total}`);
    console.log(`🔑 Sample admin: ${sampleAdmin ? sampleAdmin.email : 'none'} / ${SEED_PASSWORD}`);

    return total;
};

seedDatabase()
    .then(async (total) => {
        await mongoose.disconnect();
        console.log('🔌 Disconnected from MongoDB.');
        process.exit(total === EXPECTED_USERS ? 0 : 1);
    })
    .catch(async (error) => {
        console.error('❌ Error seeding database:', error.message);
        await mongoose.disconnect();
        console.log('🔌 Disconnected from MongoDB.');
        process.exit(1);
    });
