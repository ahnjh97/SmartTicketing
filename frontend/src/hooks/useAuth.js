import { useContext } from "react";
import { AuthContext } from "../auth/AuthContext.js";

export default function useAuth() {
    const auth = useContext(AuthContext);
    if (!auth) throw new Error("AuthProvider 안에서 useAuth를 사용해주세요.");
    return auth;
}
