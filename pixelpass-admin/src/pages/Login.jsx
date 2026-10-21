import { useState } from 'react';
import { Shield, Mail, Lock, EyeOff, Eye, ArrowRight, Phone } from 'lucide-react';

export default function Login() {
  // Form States
  const [loginMethod, setLoginMethod] = useState('email'); // 'email' or 'phone'
  const [identifier, setIdentifier] = useState('');
  const [password, setPassword] = useState('');
  const [keepSignedIn, setKeepSignedIn] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState(null);

  const handleLogin = async (e) => {
    e.preventDefault();
    setIsLoading(true);
    setError(null);

    try {
      const response = await fetch('http://localhost:3000/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ identifier, password, keepSignedIn })
      });

      const data = await response.json();

      if (response.ok) {
        // Save token to localStorage and redirect
        localStorage.setItem('pixelpass_token', data.token);
        window.location.href = '/'; 
      } else {
        setError(data.error || "Login failed");
      }
    } catch (err) {
      setError("Network error. Check connection.");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-[#F8F9FA] flex flex-col items-center justify-center p-4 font-sans">
      
      {/* Top Left Header */}
      <div className="absolute top-6 left-6 flex items-center gap-2">
        <div className="bg-[#3D2DE0] p-1.5 rounded-lg">
          <Shield className="w-5 h-5 text-white" strokeWidth={2.5} />
        </div>
        <span className="text-xl font-bold text-gray-900 tracking-tight">Sign In</span>
      </div>

      {/* Main Card */}
      <div className="w-full max-w-[420px] bg-white rounded-[2rem] shadow-[0_8px_30px_rgb(0,0,0,0.04)] relative px-8 pt-10 pb-8 mt-12">
        
        {/* Top Drag Pill */}
        <div className="absolute top-3 left-1/2 -translate-x-1/2 w-12 h-1.5 bg-gray-200 rounded-full"></div>

        <div className="text-center mb-8">
          <h1 className="text-3xl font-bold text-[#1A1F36] tracking-tight mb-2">Welcome to PixelPass</h1>
          <p className="text-gray-500">Secure Access Platform</p>
        </div>

        {error && (
          <div className="mb-6 p-3 bg-red-50 text-red-600 text-sm rounded-lg text-center border border-red-100">
            {error}
          </div>
        )}

        <form onSubmit={handleLogin} className="space-y-5">
          
          {/* Email / Phone Input */}
          <div>
            <div className="flex justify-between items-center mb-2">
              <label className="text-sm font-medium text-gray-900">
                {loginMethod === 'email' ? 'Work Email' : 'Phone Number'}
              </label>
              <button 
                type="button"
                onClick={() => setLoginMethod(loginMethod === 'email' ? 'phone' : 'email')}
                className="text-sm font-semibold text-[#3D2DE0] hover:text-[#2d21a6] transition-colors"
              >
                {loginMethod === 'email' ? 'Use phone instead' : 'Use email instead'}
              </button>
            </div>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-4 flex items-center pointer-events-none text-gray-400">
                {loginMethod === 'email' ? <Mail className="w-5 h-5" /> : <Phone className="w-5 h-5" />}
              </div>
              <input
                type={loginMethod === 'email' ? 'email' : 'tel'}
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                placeholder={loginMethod === 'email' ? "alex@company.com" : "+1 (555) 000-0000"}
                className="w-full pl-11 pr-4 py-3.5 bg-[#F3F4F6] border-none rounded-xl text-gray-900 focus:ring-2 focus:ring-[#3D2DE0] outline-none transition-all placeholder:text-gray-500"
                required
              />
            </div>
          </div>

          {/* Password Input */}
          <div>
            <div className="flex justify-between items-center mb-2">
              <label className="text-sm font-medium text-gray-900">Password</label>
              <a href="#" className="text-sm font-semibold text-[#3D2DE0] hover:text-[#2d21a6] transition-colors">
                Forgot password?
              </a>
            </div>
            <div className="relative">
              <div className="absolute inset-y-0 left-0 pl-4 flex items-center pointer-events-none text-gray-400">
                <Lock className="w-5 h-5" />
              </div>
              <input
                type={showPassword ? 'text' : 'password'}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="At least 8 characters"
                className="w-full pl-11 pr-12 py-3.5 bg-[#F3F4F6] border-none rounded-xl text-gray-900 focus:ring-2 focus:ring-[#3D2DE0] outline-none transition-all placeholder:text-gray-500"
                required
              />
              <button 
                type="button"
                onClick={() => setShowPassword(!showPassword)}
                className="absolute inset-y-0 right-0 pr-4 flex items-center text-gray-500 hover:text-gray-700"
              >
                {showPassword ? <Eye className="w-5 h-5" /> : <EyeOff className="w-5 h-5" />}
              </button>
            </div>
          </div>

          {/* Options Row: Checkbox & End-to-end */}
          <div className="flex justify-between items-center pt-1 pb-1">
            <label className="flex items-center gap-3 cursor-pointer group">
              <div className="relative flex items-center justify-center">
                <input 
                  type="checkbox"
                  checked={keepSignedIn}
                  onChange={(e) => setKeepSignedIn(e.target.checked)}
                  className="peer appearance-none w-5 h-5 rounded-md border-2 border-gray-300 checked:border-[#3D2DE0] checked:bg-[#3D2DE0] transition-colors cursor-pointer"
                />
                <div className="absolute text-white opacity-0 peer-checked:opacity-100 pointer-events-none">
                  <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth="3">
                    <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
                  </svg>
                </div>
              </div>
              <span className="text-sm text-gray-600 group-hover:text-gray-900 transition-colors">Keep me signed in</span>
            </label>
            
            <div className="flex items-center gap-1.5 text-gray-500">
              <Lock className="w-3.5 h-3.5" />
              <span className="text-xs font-medium">End-to-end</span>
            </div>
          </div>

          {/* Submit Button */}
          <button 
            type="submit"
            disabled={isLoading}
            className="w-full bg-[#3D2DE0] hover:bg-[#2d21a6] text-white font-semibold py-4 rounded-xl shadow-lg shadow-[#3D2DE0]/20 flex items-center justify-center gap-2 transition-all disabled:opacity-70 disabled:cursor-not-allowed mt-2"
          >
            {isLoading ? 'Signing in...' : 'Sign In'}
            {!isLoading && <ArrowRight className="w-5 h-5" />}
          </button>
        </form>
      </div>

      {/* Footer Texts */}
      <div className="mt-8 text-center space-y-8 max-w-sm">
        <p className="text-sm text-gray-500 leading-relaxed">
          By signing in, you agree to our <a href="#" className="font-semibold text-gray-900 underline decoration-gray-300 underline-offset-2">Terms of Service</a> & <a href="#" className="font-semibold text-gray-900 underline decoration-gray-300 underline-offset-2">Privacy Policy</a>.
        </p>
        <p className="text-xs text-gray-400">
          Secured with 256-bit encryption. <a href="#" className="font-medium text-[#3D2DE0] hover:underline">Privacy Policy</a> · <a href="#" className="font-medium text-[#3D2DE0] hover:underline">Terms</a>
        </p>
      </div>

    </div>
  );
}