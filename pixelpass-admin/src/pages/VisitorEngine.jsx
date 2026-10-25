import { useState } from 'react';
import api, { errorMessage } from '../api/client';

export default function VisitorEngine() {
  const [formData, setFormData] = useState({
    visitorName: '',
    visitorEmail: '',
    validFrom: '',
    validUntil: ''
  });
  const [generatedLink, setGeneratedLink] = useState(null);
  const [isGenerating, setIsGenerating] = useState(false);

  const handleChange = (e) => {
    setFormData({ ...formData, [e.target.name]: e.target.value });
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setIsGenerating(true);
    setGeneratedLink(null);

    try {
      const { data } = await api.post('/api/visitors/invite', formData);
      setGeneratedLink(data.link);
    } catch (err) {
      alert(errorMessage(err, 'Failed to generate invite'));
    } finally {
      setIsGenerating(false);
    }
  };

  const handleCopy = () => {
    navigator.clipboard.writeText(generatedLink);
    alert('Link copied to clipboard!');
  };

  return (
    <div className="p-8 max-w-4xl mx-auto">
      <h1 className="text-3xl font-bold text-gray-900 mb-2">Visitor Engine</h1>
      <p className="text-gray-500 mb-8">Generate temporary, time-bound biometric access credentials for guests.</p>

      <div className="bg-white rounded-xl shadow border border-gray-100 p-8">
        <form onSubmit={handleSubmit} className="space-y-6">
          
          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">Visitor Full Name</label>
              <input 
                type="text" 
                name="visitorName"
                required
                onChange={handleChange}
                className="w-full border border-gray-300 rounded-lg p-3 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all" 
                placeholder="e.g., Jane Doe" 
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">Email Address</label>
              <input 
                type="email" 
                name="visitorEmail"
                required
                onChange={handleChange}
                className="w-full border border-gray-300 rounded-lg p-3 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all" 
                placeholder="jane@example.com" 
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-6">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">Access Valid From</label>
              <input 
                type="datetime-local" 
                name="validFrom"
                required
                onChange={handleChange}
                className="w-full border border-gray-300 rounded-lg p-3 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all text-gray-700" 
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">Access Expires At</label>
              <input 
                type="datetime-local" 
                name="validUntil"
                required
                onChange={handleChange}
                className="w-full border border-gray-300 rounded-lg p-3 focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all text-gray-700" 
              />
            </div>
          </div>

          <button 
            type="submit" 
            disabled={isGenerating}
            className="w-full bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white font-bold py-3 px-4 rounded-lg shadow transition-colors mt-4"
          >
            {isGenerating ? 'Generating...' : 'Generate Secure Link'}
          </button>
        </form>

        {/* Link Generation Result */}
        {generatedLink && (
          <div className="mt-8 p-6 bg-green-50 border border-green-200 rounded-lg animate-in fade-in duration-300">
            <h3 className="text-green-800 font-bold mb-2 flex items-center gap-2">
              <span className="text-xl">✓</span> Invite Link Ready
            </h3>
            <p className="text-sm text-green-700 mb-4">
              Send this unique link to the visitor. They will use it to upload their biometric photo and receive their QR code.
            </p>
            
            <div className="flex items-center gap-2">
              <code className="flex-1 bg-white border border-green-200 p-3 rounded text-sm text-gray-800 break-all select-all">
                {generatedLink}
              </code>
              <button 
                onClick={handleCopy}
                className="bg-green-600 hover:bg-green-700 text-white px-4 py-3 rounded font-medium transition-colors"
              >
                Copy
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}