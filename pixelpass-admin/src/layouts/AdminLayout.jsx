import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import {
    LayoutDashboard,
    Users,
    UserPlus,
    ScanLine,
    UserCheck,
    ScrollText,
    LogOut,
    Shield
} from 'lucide-react';
import { clearSession, getAdminName } from '../auth/storage';

const NAV_ITEMS = [
    { to: '/dashboard', label: 'Dashboard', icon: LayoutDashboard },
    { to: '/users', label: 'User Directory', icon: Users },
    { to: '/users/register', label: 'Add / Import Users', icon: UserPlus },
    { to: '/scans', label: 'Scan Telemetry', icon: ScanLine },
    { to: '/visitors', label: 'Visitor Engine', icon: UserCheck },
    { to: '/logs', label: 'Audit Logs', icon: ScrollText }
];

// Shell for every authenticated screen: fixed sidebar plus the routed page.
export default function AdminLayout() {
    const navigate = useNavigate();
    const adminName = getAdminName();

    const handleSignOut = () => {
        clearSession();
        navigate('/login', { replace: true });
    };

    return (
        <div className="flex h-screen w-full bg-gray-50">
            <nav className="w-64 bg-gray-900 text-white p-6 flex flex-col gap-2">
                <div className="flex items-center gap-2 mb-8">
                    <div className="bg-blue-600 p-1.5 rounded-lg">
                        <Shield className="w-5 h-5 text-white" strokeWidth={2.5} />
                    </div>
                    <h2 className="text-xl font-black text-blue-400">PixelPass</h2>
                </div>

                {NAV_ITEMS.map(({ to, label, icon: Icon }) => (
                    <NavLink
                        key={to}
                        to={to}
                        className={({ isActive }) =>
                            `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                                isActive
                                    ? 'bg-blue-600 text-white'
                                    : 'text-gray-300 hover:bg-gray-800 hover:text-white'
                            }`
                        }
                    >
                        <Icon className="w-4 h-4" />
                        {label}
                    </NavLink>
                ))}

                <div className="mt-auto border-t border-gray-800 pt-4">
                    {adminName && (
                        <p className="text-xs text-gray-400 mb-3 truncate">{adminName}</p>
                    )}
                    <button
                        onClick={handleSignOut}
                        className="flex w-full items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium text-gray-300 hover:bg-gray-800 hover:text-white transition-colors"
                    >
                        <LogOut className="w-4 h-4" />
                        Sign out
                    </button>
                </div>
            </nav>

            <main className="flex-1 overflow-y-auto">
                <Outlet />
            </main>
        </div>
    );
}
