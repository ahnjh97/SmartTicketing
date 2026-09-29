import { useEffect, useState } from "react";

const API = "http://localhost:8080";

function App() {
  const [token, setToken] = useState(
      localStorage.getItem("accessToken")
  );
  const [user, setUser] = useState(null);
  const [options, setOptions] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");

  const [birthDate, setBirthDate] = useState("");
  const [preferredTheaterIds, setPreferredTheaterIds] = useState([]);
  const [preferredSeatPositions, setPreferredSeatPositions] = useState([]);

  useEffect(() => {
    const hash = window.location.hash;

    if (hash.startsWith("#token=")) {
      const oauthToken = decodeURIComponent(
          hash.substring("#token=".length)
      );

      localStorage.setItem("accessToken", oauthToken);
      setToken(oauthToken);

      window.history.replaceState(
          {},
          document.title,
          window.location.pathname
      );
    }
  }, []);

  useEffect(() => {
    if (!token) {
      setLoading(false);
      return;
    }

    loadUser();
  }, [token]);

  async function loadUser() {
    try {
      setLoading(true);
      setError("");

      const response = await fetch(`${API}/api/users/me`, {
        headers: {
          Authorization: `Bearer ${token}`,
        },
      });

      if (response.status === 401) {
        logout();
        return;
      }

      if (!response.ok) {
        throw new Error("회원 정보를 가져오지 못했습니다.");
      }

      const data = await response.json();

      setUser(data);

      if (data.birthDate) {
        setBirthDate(data.birthDate);
      }

      setPreferredTheaterIds(
          data.preferredTheaters?.map(
              (theater) => theater.theaterId
          ) ?? []
      );

      setPreferredSeatPositions(
          data.preferredSeats ?? []
      );

      if (!data.birthDate) {
        await loadOptions();
      }
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  }

  async function loadOptions() {
    const response = await fetch(
        `${API}/api/users/preference-options`,
        {
          headers: {
            Authorization: `Bearer ${token}`,
          },
        }
    );

    if (!response.ok) {
      throw new Error("선호 설정 정보를 가져오지 못했습니다.");
    }

    const data = await response.json();
    setOptions(data);
  }

  function googleLogin() {
    window.location.href =
        `${API}/oauth2/authorization/google`;
  }

  async function normalLogin() {
    try {
      setError("");

      const response = await fetch(
          `${API}/api/auth/login`,
          {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
            },
            body: JSON.stringify({
              loginId,
              password,
            }),
          }
      );

      const data = await response.json();

      if (!response.ok) {
        throw new Error(
            data.message ?? "로그인에 실패했습니다."
        );
      }

      localStorage.setItem(
          "accessToken",
          data.accessToken
      );

      setToken(data.accessToken);
    } catch (e) {
      setError(e.message);
    }
  }

  async function saveProfile() {
    try {
      setError("");

      if (!birthDate) {
        throw new Error("생년월일을 입력해주세요.");
      }

      if (
          preferredTheaterIds.length < 3 ||
          preferredTheaterIds.length > 5
      ) {
        throw new Error(
            "선호 영화관은 3~5개를 선택해주세요."
        );
      }

      if (
          preferredSeatPositions.length < 1 ||
          preferredSeatPositions.length > 6
      ) {
        throw new Error(
            "선호 좌석은 1~6개를 선택해주세요."
        );
      }

      const response = await fetch(
          `${API}/api/users/me`,
          {
            method: "PATCH",
            headers: {
              "Content-Type": "application/json",
              Authorization: `Bearer ${token}`,
            },
            body: JSON.stringify({
              birthDate,
              preferredTheaterIds,
              preferredSeatPositions,
            }),
          }
      );

      const data = await response.json();

      if (!response.ok) {
        throw new Error(
            data.message ?? "프로필 저장에 실패했습니다."
        );
      }

      setUser(data);
      alert("프로필 설정이 저장되었습니다.");
    } catch (e) {
      setError(e.message);
    }
  }

  function toggleTheater(id) {
    setPreferredTheaterIds((current) => {
      if (current.includes(id)) {
        return current.filter((value) => value !== id);
      }

      if (current.length >= 5) {
        return current;
      }

      return [...current, id];
    });
  }

  function toggleSeat(position) {
    setPreferredSeatPositions((current) => {
      if (current.includes(position)) {
        return current.filter(
            (value) => value !== position
        );
      }

      if (current.length >= 6) {
        return current;
      }

      return [...current, position];
    });
  }

  function logout() {
    localStorage.removeItem("accessToken");
    setToken(null);
    setUser(null);
    setOptions(null);
    setError("");
  }

  if (loading) {
    return (
        <div className="page">
          <div className="card">
            <h1>SmartTicketing</h1>
            <p>불러오는 중...</p>
          </div>
        </div>
    );
  }

  if (!token || !user) {
    return (
        <div className="page">
          <div className="card">
            <h1>SmartTicketing</h1>

            <p className="description">
              로그인 테스트
            </p>

            <button
                className="google-button"
                onClick={googleLogin}
            >
              Google 로그인
            </button>

            <button
                className="naver-button"
                onClick={() =>
                    (window.location.href =
                        "http://localhost:8080/oauth2/authorization/naver")
                }
            >
              Naver 로그인
            </button>

            <div className="divider">
              또는
            </div>

            <input
                type="text"
                placeholder="아이디"
                value={loginId}
                onChange={(e) =>
                    setLoginId(e.target.value)
                }
            />

            <input
                type="password"
                placeholder="비밀번호"
                value={password}
                onChange={(e) =>
                    setPassword(e.target.value)
                }
            />

            <button onClick={normalLogin}>
              일반 로그인
            </button>

            {error && (
                <p className="error">{error}</p>
            )}
          </div>
        </div>
    );
  }

  const needsProfile =
      !user.birthDate ||
      !user.preferredTheaters?.length ||
      !user.preferredSeats?.length;

  return (
      <div className="page">
        <div className="card wide">
          <div className="header">
            <div>
              <h1>SmartTicketing</h1>
              <p>로그인 성공</p>
            </div>

            <button
                className="logout-button"
                onClick={logout}
            >
              로그아웃
            </button>
          </div>

          <section>
            <h2>회원 정보</h2>

            <div className="info">
              <p>
                <strong>ID:</strong>{" "}
                {user.id}
              </p>

              <p>
                <strong>이름:</strong>{" "}
                {user.name}
              </p>

              <p>
                <strong>닉네임:</strong>{" "}
                {user.nickname}
              </p>

              <p>
                <strong>이메일:</strong>{" "}
                {user.email ?? "-"}
              </p>

              <p>
                <strong>생년월일:</strong>{" "}
                {user.birthDate ?? "미입력"}
              </p>

              <p>
                <strong>상태:</strong>{" "}
                {user.status}
              </p>
            </div>
          </section>

          {needsProfile && (
              <>
                <hr />

                <section>
                  <h2>
                    기본 설정
                  </h2>

                  <p className="description">
                    소셜 회원은 여기에서
                    생년월일과 선호 정보를
                    입력해주세요.
                  </p>

                  <label>
                    생년월일
                  </label>

                  <input
                      type="date"
                      value={birthDate}
                      onChange={(e) =>
                          setBirthDate(
                              e.target.value
                          )
                      }
                  />

                  <h3>
                    선호 영화관
                    {" "}
                    ({preferredTheaterIds.length}/5)
                  </h3>

                  {!options ? (
                      <p>
                        영화관 정보를 불러오는 중...
                      </p>
                  ) : (
                      <div className="option-grid">
                        {options.theaters.map(
                            (theater) => {
                              const selected =
                                  preferredTheaterIds.includes(
                                      theater.id
                                  );

                              return (
                                  <button
                                      key={theater.id}
                                      className={
                                        selected
                                            ? "option selected"
                                            : "option"
                                      }
                                      onClick={() =>
                                          toggleTheater(
                                              theater.id
                                          )
                                      }
                                  >
                                    <strong>
                                      {
                                        theater.name
                                      }
                                    </strong>
                                    <span>
                                                        {
                                                          theater.brand
                                                        }
                                                    </span>
                                  </button>
                              );
                            }
                        )}
                      </div>
                  )}

                  <h3>
                    선호 좌석
                  </h3>

                  {!options ? (
                      <p>
                        좌석 정보를 불러오는 중...
                      </p>
                  ) : (
                      <div className="option-grid">
                        {options.seats.map(
                            (seat) => {
                              const selected =
                                  preferredSeatPositions.includes(
                                      seat.position
                                  );

                              return (
                                  <button
                                      key={
                                        seat.position
                                      }
                                      className={
                                        selected
                                            ? "option selected"
                                            : "option"
                                      }
                                      onClick={() =>
                                          toggleSeat(
                                              seat.position
                                          )
                                      }
                                  >
                                    {
                                      seat.label
                                    }
                                  </button>
                              );
                            }
                        )}
                      </div>
                  )}

                  <button
                      className="save-button"
                      onClick={saveProfile}
                  >
                    기본 설정 저장
                  </button>

                  {error && (
                      <p className="error">
                        {error}
                      </p>
                  )}
                </section>
              </>
          )}

          {!needsProfile && (
              <section className="success-box">
                <h2>
                  기본 설정 완료
                </h2>

                <p>
                  생년월일, 선호 영화관,
                  선호 좌석이 모두 설정되어
                  있습니다.
                </p>
              </section>
          )}
        </div>
      </div>
  );
}

export default App;