import React from 'react';

interface AppProps {
    message?: string;
}

const App: React.FC<AppProps> = ({message = 'Hello IntelliJ Plugin!'}) => {
    return (
        <div className="app-container">
            <h1>{message}</h1>
        </div>
    );
};

export default App;

