import { useState } from "react";
import useAuth from "../hooks/useAuth.js";
import useAsyncAction from "../hooks/useAsyncAction.js";

export default function NicknameSetupPage() {
    const { user, saveNickname } = useAuth();
    const [nickname, setNickname] = useState(user.nickname ?? "");
    const { error, setError, pending, run } = useAsyncAction();

    function handleSubmit(event) {
        event.preventDefault();
        const value = nickname.trim();
        if (!value) return setError("닉네임을 입력해주세요.");
        if (value.length > 100) return setError("닉네임은 100자 이하로 입력해주세요.");
        run(() => saveNickname(value));
    }

    return (
        <div className="page">
            <div className="card setup-card">
                <div className="setup-badge">KAKAO</div>
                <h1>닉네임 설정</h1>
                <p className="subtitle">SmartTicketing에서 사용할 닉네임을 설정해주세요.</p>
                <form onSubmit={handleSubmit}>
                    <label htmlFor="setup-nickname">닉네임</label>
                    <input id="setup-nickname" type="text" value={nickname}
                        onChange={(event) => setNickname(event.target.value)} placeholder="닉네임을 입력해주세요."
                        maxLength={100} autoFocus required />
                    <button type="submit" disabled={pending} className="primary-button">닉네임 저장</button>
                </form>
                {error && <p className="error-message" role="alert">{error}</p>}
            </div>
        </div>
    );
}
