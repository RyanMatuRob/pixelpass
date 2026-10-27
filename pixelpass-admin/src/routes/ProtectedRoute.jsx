import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { clearSession, getRole, getToken } from '../auth/storage';

// Gate for every admin screen. The backend re-checks the token and the ADMIN role on each
// request, so this only avoids rendering an empty shell for a signed-out or non-admin visitor.
export default function ProtectedRoute() {
    const location = useLocation();
    const token = getToken();
    const role = getRole();

    if (!token || role !== 'ADMIN') {
        // A token without the admin role is useless here; drop it so login starts clean.
        if (token) {
            clearSession();
        }
        return <Navigate to="/login" replace state={{ from: location.pathname }} />;
    }

    return <Outlet />;
}

