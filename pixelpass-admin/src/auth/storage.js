// Single source of truth for what we persist between page loads.
// Keeping the keys here stops the login screen, the router guard and the API client
// from drifting apart on spelling.
export const TOKEN_KEY = 'pixelpass_token';
export const ROLE_KEY = 'pixelpass_role';
export const NAME_KEY = 'pixelpass_name';

export const getToken = () => localStorage.getItem(TOKEN_KEY);

export const getRole = () => localStorage.getItem(ROLE_KEY);

export const getAdminName = () => localStorage.getItem(NAME_KEY);

export const saveSession = ({ token, role, name }) => {
    if (token) localStorage.setItem(TOKEN_KEY, token);
    if (role) localStorage.setItem(ROLE_KEY, role);
    if (name) localStorage.setItem(NAME_KEY, name);
};

export const clearSession = () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ROLE_KEY);
    localStorage.removeItem(NAME_KEY);
};
