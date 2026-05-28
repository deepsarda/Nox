export interface GithubRelease {
    id: number;
    name: string;
    tag_name: string;
    published_at: string;
    html_url: string;
    body: string;
    assets: {
        name: string;
        browser_download_url: string;
        size: number;
    }[];
}

export async function fetchReleases(): Promise<GithubRelease[]> {
    try {
        const response = await fetch('https://api.github.com/repos/deepsarda/nox/releases', {
            headers: {
                'User-Agent': 'Nox-Website',
            },
        });
        
        if (!response.ok) {
            throw new Error(`GitHub API responded with ${response.status}`);
        }
        
        const data = await response.json();
        return data as GithubRelease[];
    } catch (e) {
        console.warn("Failed to fetch releases from GitHub, returning fallback data:", e);
        // Fallback for development / early stages before real releases exist
        return [
            {
                id: 1,
                name: "Nox v0.0.0",
                tag_name: "v0.0.0",
                published_at: new Date().toISOString(),
                html_url: "https://github.com/deepsarda/nox/releases",
                body: "Initial alpha release of the Nox compiler and VM.\n- Sandboxed execution\n- Basic Kotlin Interop",
                assets: [
                    { name: "nox-macos-arm64.tar.gz", browser_download_url: "#", size: 1024 * 1024 * 15 },
                    { name: "nox-linux-x64.tar.gz", browser_download_url: "#", size: 1024 * 1024 * 14 },
                    { name: "nox-windows-x64.zip", browser_download_url: "#", size: 1024 * 1024 * 16 },
                ]
            }
        ];
    }
}
