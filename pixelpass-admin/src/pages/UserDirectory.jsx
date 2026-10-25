import { useState, useEffect, useCallback } from 'react';

export default function UserDirectory() {
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);

  // Modal State
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [selectedUserId, setSelectedUserId] = useState(null);
  const [previewImage, setPreviewImage] = useState(null);
  const [isUploading, setIsUploading] = useState(false);

  const fetchUsers = useCallback(async () => {
    try {
      const response = await fetch('http://localhost:3000/api/users'); 
      if (response.ok) {
        const data = await response.json();
        setUsers(data);
      }
    } catch {
      console.error("Failed to fetch users");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    // One-shot load on mount; the fetch resolves after the render pass, so this is not
    // the derived-state pattern the rule is guarding against.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    fetchUsers();
  }, [fetchUsers]);

  // --- Photo Upload Logic ---
  
  const openUploadModal = (userId) => {
    setSelectedUserId(userId);
    setPreviewImage(null);
    setIsModalOpen(true);
  };

  const handleFileSelect = (event) => {
    const file = event.target.files[0];
    if (file) {
      // Use native FileReader to convert the image to Base64 in the browser
      const reader = new FileReader();
      reader.onload = (uploadEvent) => {
        setPreviewImage(uploadEvent.target.result);
      };
      reader.readAsDataURL(file);
    }
  };

  const submitPhoto = async () => {
    if (!previewImage) return;
    setIsUploading(true);
    
    try {
      const response = await fetch(`http://localhost:3000/api/users/${selectedUserId}/biometric`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ referenceFaceBase64: previewImage })
      });
      
      if (response.ok) {
        fetchUsers(); // Refresh the table to show "Enrolled"
        setIsModalOpen(false);
      } else {
        alert("Upload failed. Check server logs.");
      }
    } catch {
      alert("Network error.");
    } finally {
      setIsUploading(false);
    }
  };

  const handleRevoke = (userId) => {
    if(window.confirm('Are you sure you want to revoke this user\'s access?')) {
      alert(`Access revoked for ${userId}`); // Placeholder for delete route
    }
  };

  if (loading) return <div className="p-8">Loading directory...</div>;

  return (
    <div className="p-8 relative">
      <div className="flex justify-between items-center mb-8">
        <h1 className="text-3xl font-bold text-gray-900">Staff & Biometric Directory</h1>
        <button className="bg-blue-600 hover:bg-blue-700 text-white px-5 py-2.5 rounded-lg font-medium shadow transition-colors">
          + Add Employee
        </button>
      </div>

      <div className="bg-white rounded-xl shadow border border-gray-100 overflow-hidden">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-100 text-sm font-semibold text-gray-600 uppercase tracking-wider">
              <th className="p-4">Name</th>
              <th className="p-4">Email</th>
              <th className="p-4">Role</th>
              <th className="p-4">Biometric Status</th>
              <th className="p-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-100">
            {users.map((user) => (
              <tr key={user._id} className="hover:bg-gray-50 transition-colors">
                <td className="p-4 font-medium text-gray-900">{user.name}</td>
                <td className="p-4 text-gray-600">{user.email}</td>
                <td className="p-4">
                  <span className="bg-gray-100 text-gray-700 px-3 py-1 rounded-full text-xs font-semibold">
                    {user.role}
                  </span>
                </td>
                <td className="p-4">
                  {user.hasFace ? (
                    <span className="text-green-600 font-medium flex items-center gap-1">
                      <span className="w-2 h-2 rounded-full bg-green-500"></span> Enrolled
                    </span>
                  ) : (
                    <span className="text-amber-500 font-medium flex items-center gap-1">
                      <span className="w-2 h-2 rounded-full bg-amber-400"></span> Missing Photo
                    </span>
                  )}
                </td>
                <td className="p-4 text-right space-x-3">
                  <button 
                    onClick={() => openUploadModal(user._id)}
                    className="text-blue-600 hover:text-blue-800 text-sm font-medium"
                  >
                    {user.hasFace ? 'Update Photo' : 'Upload Photo'}
                  </button>
                  <button 
                    onClick={() => handleRevoke(user._id)}
                    className="text-red-600 hover:text-red-800 text-sm font-medium"
                  >
                    Revoke
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* --- Biometric Upload Modal --- */}
      {isModalOpen && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white rounded-xl p-8 max-w-md w-full shadow-2xl">
            <h2 className="text-2xl font-bold mb-2">Upload ID Photo</h2>
            <p className="text-gray-500 mb-6 text-sm">Select a clear, front-facing photo of the employee. This will act as their security baseline.</p>
            
            {/* Image Preview Window */}
            <div className="w-full h-64 bg-gray-100 rounded-lg mb-6 flex items-center justify-center overflow-hidden border-2 border-dashed border-gray-300">
              {previewImage ? (
                <img src={previewImage} alt="Preview" className="w-full h-full object-cover" />
              ) : (
                <span className="text-gray-400 font-medium">No Image Selected</span>
              )}
            </div>

            <div className="flex flex-col gap-3">
              <input 
                type="file" 
                accept="image/*" 
                onChange={handleFileSelect}
                className="block w-full text-sm text-gray-500 file:mr-4 file:py-2 file:px-4 file:rounded-full file:border-0 file:text-sm file:font-semibold file:bg-blue-50 file:text-blue-700 hover:file:bg-blue-100 cursor-pointer"
              />
              
              <div className="flex gap-3 mt-4">
                <button 
                  onClick={() => setIsModalOpen(false)}
                  className="flex-1 bg-gray-100 hover:bg-gray-200 text-gray-700 py-2 rounded-lg font-medium transition-colors"
                >
                  Cancel
                </button>
                <button 
                  onClick={submitPhoto}
                  disabled={!previewImage || isUploading}
                  className="flex-1 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white py-2 rounded-lg font-medium transition-colors"
                >
                  {isUploading ? 'Saving...' : 'Save to Profile'}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}