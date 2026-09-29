import ResidencePreference from "./ResidencePreference";

const MOCK_USER = {
    id: 1,
    name: "테스트 사용자",
    nickname: "test",
    email: "test@test.com",
    loginId: "test",
    birthDate: "1997-01-01",
    address: "",
    preferredTheaters: [],
    preferredSeats: [],
    linkedProviders: [],
};

export default function ResidencePreferencePreview() {
    return (
        <div className="cinema-preview-page">
            <ResidencePreference
                user={MOCK_USER}
                onSaved={(data) => {
                    console.log(
                        "저장 테스트:",
                        data
                    );
                }}
            />
        </div>
    );
}