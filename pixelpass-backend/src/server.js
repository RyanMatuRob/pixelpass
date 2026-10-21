const path = require('node:path');
require('dotenv').config({ path: path.resolve(__dirname, '..', '.env') });

const express = require('express');
const cors = require('cors');
const mongoose = require('mongoose'); // Added missing mongoose import
const authRoutes = require('./routes/auth');
const userRoutes = require('./routes/users');
const app = express();
const logRoutes = require('./routes/logs');
const visitorRoutes = require('./routes/visitors');

app.use(cors());
app.use(express.json({ limit: '10mb' }));
app.use('/api/visitors', visitorRoutes);
app.use('/api/logs', logRoutes);
app.use('/api/auth', authRoutes); 
app.use('/api/scan', require('./routes/scan'));
app.use('/api/users', userRoutes);
app.get('/health', (request, response) => {
    response.json({ status: 'ok' });
});

// Persistent database connection
mongoose.connect(process.env.MONGO_URI, {
    serverSelectionTimeoutMS: 5000 // Force Mongoose to fail after 5 seconds instead of 30
})
.then(() => {
    console.log("PixelPass MongoDB Connected");
})
.catch(err => {
    console.error("Database connection error:", err.message);
});

if (require.main === module) {
    const port = Number(process.env.PORT || 3000);
    app.listen(3000, '0.0.0.0', () => { 
    console.log("Server running on port 3000"); 
});
}

module.exports = app;