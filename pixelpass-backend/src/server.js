const path = require('node:path');
require('dotenv').config({ path: path.resolve(__dirname, '..', '.env') });

const express = require('express');
const cors = require('cors');
const mongoose = require('mongoose'); // Added missing mongoose import
const authRoutes = require('./routes/auth');
const userRoutes = require('./routes/users');
const app = express();
const logRoutes = require('./routes/logs');
const metricsRoutes = require('./routes/metrics');
const visitorRoutes = require('./routes/visitors');

const { requireAdmin } = require('./middlewares/auth');

app.use(cors());
app.use(express.json({ limit: '10mb' }));

// Device-facing endpoints: login, gate scans and visitor self-enrolment stay reachable
// without an admin session (the invite endpoints are guarded inside visitors.js).
app.use('/api/auth', authRoutes);
app.use('/api/scan', require('./routes/scan'));
app.use('/api/visitors', visitorRoutes);

// Admin console API. The console calls these under /api/admin/*; the original paths keep
// working for scripts, and both mounts require an ADMIN bearer token.
app.use('/api/users', requireAdmin, userRoutes);
app.use('/api/logs', requireAdmin, logRoutes);
app.use('/api/admin/users', requireAdmin, userRoutes);
app.use('/api/admin/logs', requireAdmin, logRoutes);
app.use('/api/admin/metrics', requireAdmin, metricsRoutes);


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
    app.listen(port, '0.0.0.0', () => { 
    console.log(`Server running on port ${port}`); 
});
}

module.exports = app;