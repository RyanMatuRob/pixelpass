import axios from 'axios';
import { clearSession, getToken } from '../auth/storage';

// Override with VITE_API_BASE_URL when the API is not on localhost.
const baseURL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:3000';

const api = axios.create({
    baseURL,
    timeout: 20000,
    headers: { 'Content-Type': 'application/json' }
});

// Every console request carries the admin session; the API rejects anonymous calls.
api.interceptors.request.use((config) => {
    const token = getToken();
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

// A 401 means the session is gone (expired, revoked or tampered with): drop it and
// send the operator back to the login screen instead of leaving a broken shell.
api.interceptors.response.use(
    (response) => response,
    (error) => {
        if (error.response?.status === 401) {
            clearSession();
            if (window.location.pathname !== '/login') {
                window.location.replace('/login');
            }
        }
        return Promise.reject(error);
    }
);

// Pulls the API's `error` field out of an axios failure for inline display.
export const errorMessage = (error, fallback = 'Request failed') =>
    error?.response?.data?.error || error?.response?.data?.message || fallback;

export default api;
