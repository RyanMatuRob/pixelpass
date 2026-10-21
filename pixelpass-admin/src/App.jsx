import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom';
import UserDirectory from './pages/UserDirectory';
import AuditLog from './pages/AuditLog';
import VisitorEngine from './pages/VisitorEngine';
import VisitorEnrollment from './pages/VisitorEnrollment';
import Login from './pages/Login';

// Placeholder Components (We will build these out properly next)
const Dashboard = () => <div className="p-8"><h1 className="text-3xl font-bold">Command Center</h1></div>;

// A simple navigation sidebar layout
const Layout = ({ children }) => (
  <div className="flex h-screen w-full bg-gray-50">
    <nav className="w-64 bg-gray-900 text-white p-6 flex flex-col gap-4">
      <h2 className="text-2xl font-black mb-6 text-blue-400">PixelPass Admin</h2>
      <Link to="/" className="hover:text-blue-300">Dashboard</Link>
      <Link to="/users" className="hover:text-blue-300">User Directory</Link>
      <Link to="/visitors" className="hover:text-blue-300">Visitor Engine</Link>
      <Link to="/logs" className="hover:text-blue-300">Audit Logs</Link>
    </nav>
    <main className="flex-1 overflow-y-auto">
      {children}
    </main>
  </div>
);

function App() {
  return (
    <Router>
      <Routes>
        {/* 1. Public Visitor Route (NO Admin Sidebar) */}
        <Route path="/enroll/:token" element={<VisitorEnrollment />} />

        {/* 2. Admin Dashboard Routes (Wrapped in Layout Sidebar) */}
        <Route path="/*" element={
          <Layout>
            <Routes>
              <Route path="/login" element={<Login />} />
              <Route path="/enroll/:token" element={<VisitorEnrollment />} />
              <Route path="/" element={<Dashboard />} />
              <Route path="/users" element={<UserDirectory />} />
              <Route path="/visitors" element={<VisitorEngine />} />
              <Route path="/logs" element={<AuditLog />} />
            </Routes>
          </Layout>
        } />
      </Routes>
    </Router>
  );
}

export default App;