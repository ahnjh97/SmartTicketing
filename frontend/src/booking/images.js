// Keep the canonical URL in the DB; let TMDB's CDN and the browser cache image bytes.
// Only rewrite known TMDB raster URLs. Other providers retain their exact contract.
export function movieImage(url, { hero = false, sizes = '100vw' } = {}) {
    if (!url) return {};
    const match = /^https:\/\/image\.tmdb\.org\/t\/p\/(?:w\d+|original)(\/[^?#]+\.(?:jpg|jpeg|png|webp))$/i.exec(url);
    if (!match) return { src: url };
    const base = 'https://image.tmdb.org/t/p/';
    return hero
        ? { src: `${base}original${match[1]}` }
        : { src: `${base}w500${match[1]}`, srcSet: `${base}w342${match[1]} 342w, ${base}w500${match[1]} 500w, ${base}w780${match[1]} 780w`, sizes };
}
