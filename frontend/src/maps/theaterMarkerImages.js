import cgv from '../assets/theater-markers/CGV.png';
import lotte from '../assets/theater-markers/LOTTE_CINEMA.png';
import megabox from '../assets/theater-markers/MEGABOX.png';

const logos = { CGV: cgv, LOTTE_CINEMA: lotte, MEGABOX: megabox };
const logoCache = new Map();
const markerCache = new Map();

export function getTheaterBrand(theater) {
    const brand = String(theater.brand ?? '').toUpperCase();
    return brand === 'CGV' ? 'CGV' : brand === 'LOTTE_CINEMA' ? 'LOTTE_CINEMA' : 'MEGABOX';
}

function markerImage(source, size) {
    const maps = window.kakao.maps;
    return new maps.MarkerImage(source, new maps.Size(size, size), {
        offset: new maps.Point(size / 2, size / 2),
        shape: 'circle',
        coords: `${size / 2},${size / 2},${size / 2}`,
    });
}

export function createTransparentMarkerImage(size) {
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}"></svg>`;
    return markerImage(`data:image/svg+xml;charset=UTF-8,${encodeURIComponent(svg)}`, size);
}

function loadImage(source) {
    return new Promise((resolve, reject) => {
        const image = new Image();
        image.onload = () => resolve(image);
        image.onerror = () => reject(new Error('영화관 로고를 불러오지 못했습니다.'));
        image.src = source;
    });
}

// Same background removal and square crop used by the preference map.
function loadLogo(brand) {
    if (logoCache.has(brand)) return logoCache.get(brand);
    const promise = loadImage(logos[brand]).then(image => {
        const canvas = document.createElement('canvas');
        const width = canvas.width = image.naturalWidth;
        const height = canvas.height = image.naturalHeight;
        const context = canvas.getContext('2d', { willReadFrequently: true });
        if (!context || !width || !height) throw new Error('영화관 로고를 처리하지 못했습니다.');
        context.drawImage(image, 0, 0);
        const data = context.getImageData(0, 0, width, height);
        let minX = width, minY = height, maxX = -1, maxY = -1;
        for (let y = 0; y < height; y++) {
            for (let x = 0; x < width; x++) {
                const index = (y * width + x) * 4;
                const [red, green, blue] = data.data.subarray(index, index + 3);
                const max = Math.max(red, green, blue);
                if (max - Math.min(red, green, blue) < 28 && max < 235) {
                    data.data[index + 3] = 0;
                }
                if (data.data[index + 3] > 0) {
                    minX = Math.min(minX, x); minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x); maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < minX || maxY < minY) throw new Error('영화관 로고 영역이 없습니다.');
        context.putImageData(data, 0, 0);
        const padding = Math.max(2, Math.round(Math.max(maxX - minX + 1, maxY - minY + 1) * 0.04));
        const x = Math.max(0, minX - padding), y = Math.max(0, minY - padding);
        const cropWidth = Math.min(width - 1, maxX + padding) - x + 1;
        const cropHeight = Math.min(height - 1, maxY + padding) - y + 1;
        const crop = document.createElement('canvas');
        const size = crop.width = crop.height = Math.max(cropWidth, cropHeight);
        const cropContext = crop.getContext('2d');
        if (!cropContext) throw new Error('영화관 로고를 잘라내지 못했습니다.');
        cropContext.drawImage(canvas, x, y, cropWidth, cropHeight,
            (size - cropWidth) / 2, (size - cropHeight) / 2, cropWidth, cropHeight);
        return loadImage(crop.toDataURL('image/png'));
    }).catch(error => { logoCache.delete(brand); throw error; });
    logoCache.set(brand, promise);
    return promise;
}

export function getTheaterMarkerImage(brand, selected, size) {
    const key = `${brand}:${selected}:${size}`;
    if (markerCache.has(key)) return markerCache.get(key);
    const promise = loadLogo(brand).then(logo => {
        const canvas = document.createElement('canvas');
        const pixels = canvas.width = canvas.height = Math.round(size * 2);
        const context = canvas.getContext('2d');
        if (!context) throw new Error('영화관 마커를 생성하지 못했습니다.');
        const center = pixels / 2, radius = center - 3, border = selected ? 6 : 5;
        context.beginPath();
        context.arc(center, center, radius, 0, Math.PI * 2);
        context.fillStyle = '#fff'; context.fill();
        context.lineWidth = border; context.strokeStyle = selected ? '#ffc426' : '#111'; context.stroke();
        context.save();
        context.beginPath();
        context.arc(center, center, radius - border / 2, 0, Math.PI * 2);
        context.clip();
        const logoSize = (pixels - border * 2) * 1.35;
        context.drawImage(logo, center - logoSize / 2, center - logoSize / 2, logoSize, logoSize);
        context.restore();
        return markerImage(canvas.toDataURL('image/png'), size);
    }).catch(error => { markerCache.delete(key); throw error; });
    markerCache.set(key, promise);
    return promise;
}
