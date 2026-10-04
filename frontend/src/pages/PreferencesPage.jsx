import { useNavigate } from "react-router-dom";
import { PAGE_PATHS } from "../navigation.js";
import useAuth from "../hooks/useAuth.js";
import ResidencePreference from "../components/ResidencePreference.jsx";

export default function PreferencesPage() {
    const { user, updateUser } = useAuth();
    const navigate = useNavigate();
    function handleSaved(data) {
        updateUser(data);
        navigate(PAGE_PATHS.profile, { replace: true, state: { message: "회원정보가 저장되었습니다." } });
    }
    return (
        <div className="page">
            <div className="card profile-card preference-card">
                <div className="profile-header">
                    <div>
                        <h1>회원정보 수정</h1>
                        <p className="subtitle">위치, 선호 영화관, 선호 좌석을 수정합니다.</p>
                    </div>
                    <button type="button" className="logout-button"
                        onClick={() => navigate(PAGE_PATHS.profile, { replace: true })}>취소</button>
                </div>
                <ResidencePreference key={user.id} user={user} onSaved={handleSaved} />
            </div>
        </div>
    );
}
