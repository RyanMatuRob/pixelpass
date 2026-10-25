import { useCallback, useEffect, useState } from 'react';
import { Search, Users, Upload, X, ShieldCheck, ShieldOff, Filter } from 'lucide-react';
import api, { errorMessage } from '../api/client';

const ROLE_OPTIONS = ['ADMIN', 'SECURITY', 'STAFF', 'STUDENT', 'VISITOR'];
const STATUS_OPTIONS = [
  { value: '', label: 'All statuses' },
  { value: 'active', label: 'Active' },
  { value: 'revoked', label: 'Revoked' }
];

const ROLE_BADGE = {
  ADMIN: 'bg-purple-100 text-purple-700',
  SECURITY: 'bg-blue-100 text-blue-700',
  STAFF: 'bg-slate-100 text-slate-700',
  STUDENT: 'bg-emerald-100 text-emerald-700',
  VISITOR: 'bg-amber-100 text-amber-700'
};

const fullName = (user) => [user.firstName, user.lastName].filter(Boolean).join(' ') || user.email;

export default function UserDirectory() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  // Filters (search is debounced so typing does not hammer the API)
  const [search, setSearch] = useState('');
  const [debouncedSearch, setDebouncedSearch] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');

  const [busyId, setBusyId] = useState(null);

  // Biometric upload modal
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [selectedUser, setSelectedUser] = useState(null);
  const [previewImage, setPreviewImage] = useState(null);
  const [isUploading, setIsUploading] = useState(false);

  useEffect(() => {
    const timer = setTimeout(() => setDebouncedSearch(search), 300);
    return () => clearTimeout(timer);
  }, [search]);

  const loadUsers = useCallback(async () => {
    setLoading(true);
    try {
      const { data } = await api.get('/api/users', {
        params: {
          search: debouncedSearch || undefined,
          roleCategory: roleFilter || undefined,
          status: statusFilter || undefined
        }
      });
      setUsers(data);
      setError(null);
    } catch (err) {
      setError(errorMessage(err, 'Could not load the user directory'));
    } finally {
      setLoading(false);
    }
  }, [debouncedSearch, roleFilter, statusFilter]);

  useEffect(() => {
    // Filter-driven load; the request resolves after the render pass.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    loadUsers();
  }, [loadUsers]);

  const toggleAccess = async (user) => {
    setBusyId(user._id);
    try {
      const { data } = await api.patch(`/api/users/${user._id}/revoke`, { isActive: !user.isActive });
      setUsers((prev) => prev.map((row) => (row._id === user._id ? { ...row, ...data.user } : row)));
      setError(null);
    } catch (err) {
      setError(errorMessage(err, 'Could not update access'));
    } finally {
      setBusyId(null);
    }
  };

  const openPhotoModal = (user) => {
    setSelectedUser(user);
    setPreviewImage(null);
    setIsModalOpen(true);
  };

  const handleFileChange = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onloadend = () => setPreviewImage(reader.result);
    reader.readAsDataURL(file);
  };

  const submitPhoto = async () => {
    if (!previewImage || !selectedUser) return;
    setIsUploading(true);

    try {
      await api.put(`/api/users/${selectedUser._id}/biometric`, { referenceFaceBase64: previewImage });
      setIsModalOpen(false);
      await loadUsers();
    } catch (err) {
      setError(errorMessage(err, 'Upload failed. Check the server logs.'));
    } finally {
      setIsUploading(false);
    }
  };

  const revokedCount = users.filter((user) => !user.isActive).length;

  return (
    <div className="p-8 space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold text-gray-900">User Directory</h1>
          <p className="text-gray-500 mt-1">
            {loading && users.length === 0
              ? 'Loading accounts…'
              : `${users.length} account${users.length === 1 ? '' : 's'} shown · ${revokedCount} revoked`}
          </p>
        </div>
        <div className="flex items-center gap-2 text-sm text-gray-500 bg-white px-4 py-2 rounded-lg border shadow-sm">
          <Users className="w-4 h-4" />
          {users.length} visible
        </div>
      </header>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <section className="bg-white rounded-xl shadow border border-gray-100">
        <div className="flex flex-wrap items-center gap-3 border-b border-gray-100 p-4">
          <div className="relative flex-1 min-w-[240px]">
            <Search className="w-4 h-4 text-gray-400 absolute left-3 top-1/2 -translate-y-1/2" />
            <input
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Search by name, email or user id"
              className="w-full rounded-lg border border-gray-200 py-2 pl-9 pr-3 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
            />
          </div>

          <div className="flex items-center gap-2 text-sm text-gray-500">
            <Filter className="w-4 h-4" />
            <select
              value={roleFilter}
              onChange={(event) => setRoleFilter(event.target.value)}
              className="rounded-lg border border-gray-200 py-2 pl-3 pr-8 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
            >
              <option value="">All roles</option>
              {ROLE_OPTIONS.map((role) => (
                <option key={role} value={role}>{role}</option>
              ))}
            </select>

            <select
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
              className="rounded-lg border border-gray-200 py-2 pl-3 pr-8 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
            >
              {STATUS_OPTIONS.map((option) => (
                <option key={option.value} value={option.value}>{option.label}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="min-w-full divide-y divide-gray-100 text-sm">
            <thead className="bg-gray-50 text-xs uppercase tracking-wide text-gray-500">
              <tr>
                <th className="px-6 py-3 text-left font-semibold">Name</th>
                <th className="px-6 py-3 text-left font-semibold">Email</th>
                <th className="px-6 py-3 text-left font-semibold">Role</th>
                <th className="px-6 py-3 text-left font-semibold">Status</th>
                <th className="px-6 py-3 text-left font-semibold">Biometrics</th>
                <th className="px-6 py-3 text-right font-semibold">Access</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {loading && users.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-6 py-10 text-center text-gray-500">Loading accounts…</td>
                </tr>
              )}

              {!loading && users.length === 0 && (
                <tr>
                  <td colSpan={6} className="px-6 py-10 text-center text-gray-500">
                    No accounts match these filters.
                  </td>
                </tr>
              )}

              {users.map((user) => (
                <tr key={user._id} className={user.isActive ? 'hover:bg-gray-50' : 'bg-red-50/40 hover:bg-red-50'}>
                  <td className="px-6 py-4">
                    <p className={`font-medium ${user.isActive ? 'text-gray-900' : 'text-red-800'}`}>{fullName(user)}</p>
                    {user.department && <p className="text-xs text-gray-500">{user.department}</p>}
                  </td>
                  <td className="px-6 py-4 text-gray-600">{user.email}</td>
                  <td className="px-6 py-4">
                    <span className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-semibold ${ROLE_BADGE[user.roleCategory] || 'bg-gray-100 text-gray-700'}`}>
                      {user.roleCategory}
                    </span>
                  </td>
                  <td className="px-6 py-4">
                    {user.isActive ? (
                      <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-emerald-700">
                        <ShieldCheck className="w-4 h-4" /> Active
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-red-700">
                        <ShieldOff className="w-4 h-4" /> Revoked
                      </span>
                    )}
                  </td>
                  <td className="px-6 py-4">
                    {user.hasFace ? (
                      <span className="text-xs font-semibold text-emerald-700">Enrolled</span>
                    ) : (
                      <button
                        onClick={() => openPhotoModal(user)}
                        className="inline-flex items-center gap-1.5 text-xs font-semibold text-blue-600 hover:text-blue-700"
                      >
                        <Upload className="w-3.5 h-3.5" /> Upload
                      </button>
                    )}
                  </td>
                  <td className="px-6 py-4 text-right">
                    <button
                      onClick={() => toggleAccess(user)}
                      disabled={busyId === user._id}
                      className={`inline-flex items-center gap-2 rounded-lg px-3 py-1.5 text-xs font-bold transition-colors disabled:opacity-50 ${
                        user.isActive
                          ? 'bg-red-600 text-white hover:bg-red-700'
                          : 'bg-emerald-600 text-white hover:bg-emerald-700'
                      }`}
                    >
                      {busyId === user._id
                        ? 'Working…'
                        : user.isActive
                          ? 'Revoke Access'
                          : 'Restore Access'}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {isModalOpen && selectedUser && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
          <div className="w-full max-w-md rounded-xl bg-white p-6 shadow-2xl">
            <div className="flex items-start justify-between">
              <div>
                <h2 className="text-lg font-bold text-gray-900">Upload reference photo</h2>
                <p className="text-sm text-gray-500">{fullName(selectedUser)}</p>
              </div>
              <button onClick={() => setIsModalOpen(false)} className="text-gray-400 hover:text-gray-600">
                <X className="w-5 h-5" />
              </button>
            </div>

            <div className="mt-5 space-y-4">
              <input
                type="file"
                accept="image/*"
                onChange={handleFileChange}
                className="block w-full text-sm text-gray-600 file:mr-3 file:rounded-lg file:border-0 file:bg-blue-600 file:px-3 file:py-2 file:text-sm file:font-semibold file:text-white hover:file:bg-blue-700"
              />

              {previewImage && (
                <img src={previewImage} alt="Selected reference" className="mx-auto h-40 w-40 rounded-lg object-cover" />
              )}

              <button
                onClick={submitPhoto}
                disabled={!previewImage || isUploading}
                className="w-full rounded-lg bg-blue-600 py-2.5 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
              >
                {isUploading ? 'Uploading…' : 'Save reference photo'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
