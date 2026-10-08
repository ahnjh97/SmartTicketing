import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import AdmissionGate from './components/AdmissionGate.jsx';
import { HashRouter } from "react-router-dom";
import "./index.css";
import "./styles/tokens.css";

ReactDOM.createRoot(document.getElementById("root")).render(
    <React.StrictMode>
        <HashRouter>
            <AdmissionGate><App /></AdmissionGate>
        </HashRouter>
    </React.StrictMode>
);
