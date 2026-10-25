import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { getToken } from '../auth/storage';

// Gate for every admin screen. The backend re-checks the token on each request, so this
// only exists to avoid rendering an empty shell for a signed-out visitor.
export default function ProtectedRoute() {
    const location = useLocation();
    const token = getToken();

    if (!token) {
        return <Navigate to="/login" replace state={{ from: location.pathname }} />;
    }

    return <Outlet />;
}
