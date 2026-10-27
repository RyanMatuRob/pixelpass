import { BrowserRouter as Router, Routes, Route, Navigate } from 'react-router-dom';
import AdminLayout from './layouts/AdminLayout';
import ProtectedRoute from './routes/ProtectedRoute';
import Dashboard from './pages/Dashboard';
import Scans from './pages/Scans';
import UserDirectory from './pages/UserDirectory';
import UserRegistration from './pages/UserRegistration';
import VisitorEngine from './pages/VisitorEngine';
import VisitorEnrollment from './pages/VisitorEnrollment';
import VisitorCapture from './pages/VisitorCapture';
import AuditLog from './pages/AuditLog';
import Login from './pages/Login';

function App() {
    return (
        <Router>
            <Routes>
                {/* Public: no admin shell, no session required */}
                <Route path="/login" element={<Login />} />
                <Route path="/invite" element={<VisitorCapture />} />
                <Route path="/invite/:token" element={<VisitorCapture />} />
                <Route path="/enroll/:token" element={<VisitorEnrollment />} />
                <Route path="/enroll" element={<VisitorEnrollment />} />

                {/* Authenticated console */}
                <Route element={<ProtectedRoute />}>
                    <Route element={<AdminLayout />}>
                        <Route index element={<Navigate to="/dashboard" replace />} />
                        <Route path="dashboard" element={<Dashboard />} />
                        <Route path="users" element={<UserDirectory />} />
                        <Route path="users/register" element={<UserRegistration />} />
                        <Route path="scans" element={<Scans />} />
                        <Route path="visitors" element={<VisitorEngine />} />
                        <Route path="logs" element={<AuditLog />} />
                    </Route>
                </Route>

                {/* Unknown URLs belong behind the guard, so let it decide where to send people */}
                <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
        </Router>
    );
}

export default App;
