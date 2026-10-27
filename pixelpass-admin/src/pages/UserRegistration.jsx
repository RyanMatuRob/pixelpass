import { useState } from 'react';
import { UserPlus, Upload, FileText, CheckCircle, AlertCircle, ArrowLeft, Image as ImageIcon } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';
import api, { errorMessage } from '../api/client';

const ROLE_OPTIONS = ['STAFF', 'STUDENT', 'SECURITY', 'ADMIN', 'VISITOR'];

export default function UserRegistration() {
  const navigate = useNavigate();
  const [activeTab, setActiveTab] = useState('single'); // 'single' or 'batch'

  // Single User Form State
  const [singleForm, setSingleForm] = useState({
    firstName: '',
    lastName: '',
    email: '',
    phoneNumber: '',
    roleCategory: 'STAFF',
    department: '',
    password: '',
    referenceFaceBase64: ''
  });
  const [photoPreview, setPhotoPreview] = useState(null);
  const [singleSubmitting, setSingleSubmitting] = useState(false);
  const [singleSuccess, setSingleSuccess] = useState(null);
  const [singleError, setSingleError] = useState(null);

  // Batch CSV State
  const [csvFile, setCsvFile] = useState(null);
  const [parsedRows, setParsedRows] = useState([]);
  const [batchSubmitting, setBatchSubmitting] = useState(false);
  const [batchResults, setBatchResults] = useState(null);
  const [batchError, setBatchError] = useState(null);

  // Handle single photo upload conversion to Base64
  const handlePhotoChange = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onloadend = () => {
      const base64Data = reader.result;
      setPhotoPreview(base64Data);
      setSingleForm((prev) => ({ ...prev, referenceFaceBase64: base64Data }));
    };
    reader.readAsDataURL(file);
  };

  const handleSingleSubmit = async (event) => {
    event.preventDefault();
    setSingleSubmitting(true);
    setSingleError(null);
    setSingleSuccess(null);

    try {
      const { data } = await api.post('/api/users', singleForm);
      setSingleSuccess(data);
      setSingleForm({
        firstName: '',
        lastName: '',
        email: '',
        phoneNumber: '',
        roleCategory: 'STAFF',
        department: '',
        password: '',
        referenceFaceBase64: ''
      });
      setPhotoPreview(null);
    } catch (err) {
      setSingleError(errorMessage(err, 'Failed to create user account'));
    } finally {
      setSingleSubmitting(false);
    }
  };

  // Simple CSV parser
  const handleCsvFileChange = (event) => {
    const file = event.target.files?.[0];
    if (!file) return;

    setCsvFile(file);
    setBatchResults(null);
    setBatchError(null);

    const reader = new FileReader();
    reader.onload = (e) => {
      const text = e.target.result;
      parseCsvText(text);
    };
    reader.readAsText(file);
  };

  const parseCsvText = (text) => {
    const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
    if (lines.length < 2) {
      setBatchError('CSV file must contain a header row and at least one data row');
      setParsedRows([]);
      return;
    }

    const headers = lines[0].split(',').map((h) => h.trim().replace(/^["']|["']$/g, ''));
    const rows = [];

    for (let i = 1; i < lines.length; i++) {
      // Split by comma ignoring commas inside quotes
      const values = lines[i].split(/,(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)/).map((v) => v.trim().replace(/^["']|["']$/g, ''));
      const rowObj = {};

      headers.forEach((header, idx) => {
        const val = values[idx] || '';
        const key = header.toLowerCase();

        if (key.includes('first')) rowObj.firstName = val;
        else if (key.includes('last')) rowObj.lastName = val;
        else if (key.includes('email')) rowObj.email = val;
        else if (key.includes('phone')) rowObj.phoneNumber = val;
        else if (key.includes('role')) rowObj.roleCategory = val.toUpperCase();
        else if (key.includes('dept') || key.includes('department')) rowObj.department = val;
        else if (key.includes('pass')) rowObj.password = val;
        else if (key.includes('photo') || key.includes('face') || key.includes('base64')) rowObj.referenceFaceBase64 = val;
      });

      // Quick validation
      rowObj.isValid = Boolean(rowObj.firstName && rowObj.lastName && rowObj.email && rowObj.roleCategory);
      rowObj.validationError = rowObj.isValid ? null : 'Missing firstName, lastName, email, or roleCategory';

      rows.push(rowObj);
    }

    setParsedRows(rows);
  };

  const handleBatchSubmit = async () => {
    const validUsers = parsedRows.filter((r) => r.isValid);
    if (validUsers.length === 0) {
      setBatchError('No valid user records found to import');
      return;
    }

    setBatchSubmitting(true);
    setBatchError(null);

    try {
      const { data } = await api.post('/api/users/batch', { users: validUsers });
      setBatchResults(data);
    } catch (err) {
      setBatchError(errorMessage(err, 'Failed to process batch CSV import'));
    } finally {
      setBatchSubmitting(false);
    }
  };

  return (
    <div className="p-8 space-y-6 max-w-5xl mx-auto">
      <header className="flex items-center justify-between">
        <div>
          <Link to="/users" className="inline-flex items-center gap-1 text-sm font-semibold text-blue-600 hover:text-blue-700 mb-2">
            <ArrowLeft className="w-4 h-4" /> Back to User Directory
          </Link>
          <h1 className="text-3xl font-bold text-gray-900">Add / Import Users</h1>
          <p className="text-gray-500 mt-1">Register individual users or batch upload from CSV records.</p>
        </div>
      </header>

      {/* Tabs */}
      <div className="flex border-b border-gray-200">
        <button
          onClick={() => setActiveTab('single')}
          className={`flex items-center gap-2 py-3 px-6 text-sm font-semibold border-b-2 transition-colors ${
            activeTab === 'single'
              ? 'border-blue-600 text-blue-600'
              : 'border-transparent text-gray-500 hover:text-gray-700'
          }`}
        >
          <UserPlus className="w-4 h-4" /> Single User Onboarding
        </button>
        <button
          onClick={() => setActiveTab('batch')}
          className={`flex items-center gap-2 py-3 px-6 text-sm font-semibold border-b-2 transition-colors ${
            activeTab === 'batch'
              ? 'border-blue-600 text-blue-600'
              : 'border-transparent text-gray-500 hover:text-gray-700'
          }`}
        >
          <FileText className="w-4 h-4" /> Batch CSV Import
        </button>
      </div>

      {/* TAB 1: Single User Onboarding Form */}
      {activeTab === 'single' && (
        <section className="bg-white rounded-xl shadow border border-gray-100 p-6">
          {singleSuccess && (
            <div className="mb-6 rounded-lg border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-800">
              <div className="flex items-center gap-2 font-bold mb-1">
                <CheckCircle className="w-5 h-5 text-emerald-600" /> User Created Successfully
              </div>
              <p>Account <strong>{singleSuccess.user.firstName} {singleSuccess.user.lastName}</strong> ({singleSuccess.user.email}) registered with ID <code>{singleSuccess.user.userId}</code>.</p>
              {singleSuccess.user.tempPassword && (
                <p className="mt-2 text-xs text-emerald-700 font-mono bg-emerald-100/60 p-2 rounded">
                  Temporary Password: <strong>{singleSuccess.user.tempPassword}</strong>
                </p>
              )}
            </div>
          )}

          {singleError && (
            <div className="mb-6 rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 flex items-center gap-2">
              <AlertCircle className="w-5 h-5 text-red-600 flex-shrink-0" /> {singleError}
            </div>
          )}

          <form onSubmit={handleSingleSubmit} className="space-y-6">
            <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
              <div>
                <label className="block text-xs font-semibold uppercase text-gray-600 mb-1">First Name *</label>
                <input
                  type="text"
                  required
                  value={singleForm.firstName}
                  onChange={(e) => setSingleForm({ ...singleForm, firstName: e.target.value })}
                  placeholder="e.g. Alex"
                  className="w-full rounded-lg border border-gray-200 p-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-gray-600 mb-1">Last Name *</label>
                <input
                  type="text"
                  required
                  value={singleForm.lastName}
                  onChange={(e) => setSingleForm({ ...singleForm, lastName: e.target.value })}
                  placeholder="e.g. Mercer"
                  className="w-full rounded-lg border border-gray-200 p-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-gray-600 mb-1">Email Address *</label>
                <input
                  type="email"
                  required
                  value={singleForm.email}
                  onChange={(e) => setSingleForm({ ...singleForm, email: e.target.value })}
                  placeholder="alex.mercer@company.com"
                  className="w-full rounded-lg border border-gray-200 p-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-gray-600 mb-1">Phone Number (Optional)</label>
                <input
                  type="text"
                  value={singleForm.phoneNumber}
                  onChange={(e) => setSingleForm({ ...singleForm, phoneNumber: e.target.value })}
                  placeholder="+254712345678"
                  className="w-full rounded-lg border border-gray-200 p-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-gray-600 mb-1">Role Category *</label>
                <select
                  value={singleForm.roleCategory}
                  onChange={(e) => setSingleForm({ ...singleForm, roleCategory: e.target.value })}
                  className="w-full rounded-lg border border-gray-200 p-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
                >
                  {ROLE_OPTIONS.map((role) => (
                    <option key={role} value={role}>{role}</option>
                  ))}
                </select>
              </div>

              <div>
                <label className="block text-xs font-semibold uppercase text-gray-600 mb-1">Department (Optional)</label>
                <input
                  type="text"
                  value={singleForm.department}
                  onChange={(e) => setSingleForm({ ...singleForm, department: e.target.value })}
                  placeholder="e.g. Engineering"
                  className="w-full rounded-lg border border-gray-200 p-2.5 text-sm focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-100"
                />
              </div>
            </div>

            <div>
              <label className="block text-xs font-semibold uppercase text-gray-600 mb-1">Reference Face Photo (Optional)</label>
              <div className="flex items-center gap-4">
                <input
                  type="file"
                  accept="image/*"
                  onChange={handlePhotoChange}
                  className="block text-sm text-gray-600 file:mr-3 file:rounded-lg file:border-0 file:bg-blue-600 file:px-3 file:py-2 file:text-sm file:font-semibold file:text-white hover:file:bg-blue-700"
                />
                {photoPreview && (
                  <img src={photoPreview} alt="Face preview" className="h-16 w-16 rounded-lg object-cover border" />
                )}
              </div>
            </div>

            <button
              type="submit"
              disabled={singleSubmitting}
              className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
            >
              <UserPlus className="w-4 h-4" /> {singleSubmitting ? 'Creating Account…' : 'Register User'}
            </button>
          </form>
        </section>
      )}

      {/* TAB 2: Batch CSV Import */}
      {activeTab === 'batch' && (
        <section className="bg-white rounded-xl shadow border border-gray-100 p-6 space-y-6">
          <div className="border-2 border-dashed border-gray-200 rounded-xl p-8 text-center bg-gray-50/50">
            <Upload className="w-10 h-10 text-gray-400 mx-auto mb-3" />
            <p className="text-sm font-semibold text-gray-700">Select a CSV file to upload users in bulk</p>
            <p className="text-xs text-gray-500 mt-1">Header row format: <code>firstName, lastName, email, phoneNumber, roleCategory, department, password</code></p>

            <input
              type="file"
              accept=".csv,text/csv"
              onChange={handleCsvFileChange}
              className="mt-4 inline-block text-sm text-gray-600 file:mr-3 file:rounded-lg file:border-0 file:bg-blue-600 file:px-3 file:py-2 file:text-sm file:font-semibold file:text-white hover:file:bg-blue-700"
            />
          </div>

          {batchError && (
            <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm text-red-700 flex items-center gap-2">
              <AlertCircle className="w-5 h-5 text-red-600 flex-shrink-0" /> {batchError}
            </div>
          )}

          {parsedRows.length > 0 && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <h3 className="text-md font-bold text-gray-900">CSV Preview ({parsedRows.length} rows parsed)</h3>
                <button
                  onClick={handleBatchSubmit}
                  disabled={batchSubmitting || parsedRows.filter((r) => r.isValid).length === 0}
                  className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-50"
                >
                  <Upload className="w-4 h-4" /> {batchSubmitting ? 'Importing…' : `Import ${parsedRows.filter((r) => r.isValid).length} Valid Accounts`}
                </button>
              </div>

              <div className="overflow-x-auto rounded-lg border border-gray-200">
                <table className="min-w-full divide-y divide-gray-200 text-xs">
                  <thead className="bg-gray-50 text-gray-500 font-semibold uppercase">
                    <tr>
                      <th className="px-3 py-2 text-left">Status</th>
                      <th className="px-3 py-2 text-left">First Name</th>
                      <th className="px-3 py-2 text-left">Last Name</th>
                      <th className="px-3 py-2 text-left">Email</th>
                      <th className="px-3 py-2 text-left">Role</th>
                      <th className="px-3 py-2 text-left">Department</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {parsedRows.map((row, idx) => (
                      <tr key={idx} className={row.isValid ? 'hover:bg-gray-50' : 'bg-red-50/50'}>
                        <td className="px-3 py-2">
                          {row.isValid ? (
                            <span className="text-emerald-600 font-semibold flex items-center gap-1"><CheckCircle className="w-3.5 h-3.5" /> Valid</span>
                          ) : (
                            <span className="text-red-600 font-semibold" title={row.validationError}>Invalid</span>
                          )}
                        </td>
                        <td className="px-3 py-2 font-medium text-gray-900">{row.firstName || '—'}</td>
                        <td className="px-3 py-2 font-medium text-gray-900">{row.lastName || '—'}</td>
                        <td className="px-3 py-2 text-gray-600">{row.email || '—'}</td>
                        <td className="px-3 py-2 text-gray-600">{row.roleCategory || '—'}</td>
                        <td className="px-3 py-2 text-gray-500">{row.department || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}

          {batchResults && (
            <div className="rounded-lg border border-blue-200 bg-blue-50 p-4 space-y-3 text-sm text-blue-900">
              <p className="font-bold">{batchResults.message}</p>
              {batchResults.createdCount > 0 && (
                <p className="text-xs text-emerald-700">Successfully created {batchResults.createdCount} user accounts.</p>
              )}
              {batchResults.failedCount > 0 && (
                <div className="text-xs text-red-700 space-y-1">
                  <p className="font-semibold">Failed entries ({batchResults.failedCount}):</p>
                  <ul className="list-disc pl-5">
                    {batchResults.failed.map((f, i) => (
                      <li key={i}>{f.email}: {f.reason}</li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}
        </section>
      )}
    </div>
  );
}
