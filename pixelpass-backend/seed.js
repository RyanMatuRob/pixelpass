require('dotenv').config();
const mongoose = require('mongoose');
const bcrypt = require('bcryptjs');
const User = require('./src/models/User'); 

const seedDatabase = async () => {
    try {
        await mongoose.connect(process.env.MONGO_URI);
        console.log("Connected to Atlas for seeding...");

        // Clear existing user data to prevent duplicate key errors
        await User.deleteMany({});
        console.log("Cleared existing users.");

        // Hash a universal test password
        const salt = await bcrypt.genSalt(10);
        const hashedPassword = await bcrypt.hash('password123', salt);

        // Define mock users covering all system roles[cite: 6]
        const mockUsers = [
            {
                userId: 'ADM-001',
                email: 'admin@company.com', // Matches your UI placeholder[cite: 5]
                password: hashedPassword,
                roleCategory: 'ADMIN' //[cite: 6]
            },
            {
                userId: 'STF-001',
                email: 'staff@company.com',
                password: hashedPassword,
                roleCategory: 'STAFF' //[cite: 6]
            },
            {
                userId: 'STU-001',
                email: 'student@company.com',
                password: hashedPassword,
                roleCategory: 'STUDENT' //[cite: 6]
            },
            {
                userId: 'SEC-001',
                email: 'guard@company.com',
                password: hashedPassword,
                roleCategory: 'SECURITY' //[cite: 6]
            },
            {
                userId: 'VIS-001',
                email: 'visitor@company.com',
                password: hashedPassword,
                roleCategory: 'VISITOR' //[cite: 6]
            }
        ];

        // Insert into MongoDB
        await User.insertMany(mockUsers);
        console.log("Database successfully seeded with 4 test accounts!");
        
        // Terminate the script safely
        process.exit();
    } catch (error) {
        console.error("Seeding failed:", error);
        process.exit(1);
    }
};

seedDatabase();