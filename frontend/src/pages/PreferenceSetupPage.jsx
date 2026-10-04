import { useNavigate } from "react-router-dom";
import { PAGE_PATHS } from "../navigation.js";
import useAuth from "../hooks/useAuth.js";
import ResidencePreference from "../components/ResidencePreference.jsx";
import { bookingReturn } from '../booking/state.js';

export default function PreferenceSetupPage() {
    const { user, updateUser } = useAuth();
    const navigate = useNavigate();
    function handleSaved(data) {
        updateUser(data);
        navigate(bookingReturn() || PAGE_PATHS.home, { replace: true, state: { message: "회원정보가 저장되었습니다." } });
    }
    return (
        <div className="page">
            <div className="card profile-card">
                <div className="profile-header">
                    <div>
                        <h1>선호 정보 설정</h1>
                        <p className="subtitle">{user.nickname}님, 예매에 사용할 선호 정보를 설정해주세요.</p>
                    </div>
                </div>
                <ResidencePreference key={user.id} user={user} onSaved={handleSaved} />
            </div>
        </div>
    );
}
