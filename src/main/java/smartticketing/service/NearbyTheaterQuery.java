package smartticketing.service;

import jakarta.persistence.EntityManager;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import smartticketing.entity.enums.TheaterBrand;
import java.util.List;

/** Spatial candidate filtering and exact spherical distance ordering stay in MySQL. */
@Service
@DependsOn("theaterSpatialSchema")
@Transactional(readOnly=true)
public class NearbyTheaterQuery {
    private static final double EARTH_RADIUS=6_371_000;
    private static final double WORLD_RADIUS=Math.PI*EARTH_RADIUS+1;
    private final EntityManager em;
    public NearbyTheaterQuery(EntityManager em) { this.em=em; }

    public record Match(Long id, String name, TheaterBrand brand, String address, String kakaoPlaceId,
                        double latitude, double longitude, int distanceMeters) {}

    public static final String SEARCH_SQL="""
            select t.id,t.name,t.brand,t.address,t.kakao_place_id,t.latitude,t.longitude,
                   st_distance_sphere(p.location,point(:longitude,:latitude),6371000) as distance_m
            from theater_locations p force index (idx_theater_locations_point)
            straight_join theaters t on t.id=p.theater_id
            where t.is_active=true and mbrintersects(p.location,st_geomfromtext(:bounds,0))
            having distance_m<=:radius
            order by distance_m,t.name,t.id limit 12
            """;

    public List<Match> findNearest(double latitude,double longitude) {
        validateCoordinates(latitude,longitude);
        double normalizedLongitude=longitude==-180 ? 180 : longitude;
        for (double radius=2000;; radius=Math.min(radius*2,WORLD_RADIUS)) {
            var rows=em.createNativeQuery(SEARCH_SQL,Object[].class)
                    .setParameter("latitude",latitude).setParameter("longitude",normalizedLongitude)
                    .setParameter("bounds",bounds(latitude,normalizedLongitude,radius))
                    .setParameter("radius",radius).getResultList();
            // The rectangle contains the entire spherical cap. Every omitted point is farther
            // than this radius, so 12 results inside the cap are the global nearest 12.
            if (rows.size()==12 || radius==WORLD_RADIUS) return rows.stream().map(raw -> {
                Object[] r=(Object[])raw;
                return new Match(((Number)r[0]).longValue(),(String)r[1],TheaterBrand.valueOf((String)r[2]),
                        (String)r[3],(String)r[4],((Number)r[5]).doubleValue(),((Number)r[6]).doubleValue(),
                        (int)Math.round(((Number)r[7]).doubleValue()));
            }).toList();
        }
    }

    public static void validateCoordinates(double latitude,double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude) || Math.abs(latitude)>90 || Math.abs(longitude)>180)
            throw new IllegalArgumentException("위도는 -90~90, 경도는 -180~180 범위여야 합니다.");
    }

    private static String bounds(double latitude,double longitude,double radius) {
        double angle=Math.min(Math.PI,radius/EARTH_RADIUS);
        double lat=Math.toRadians(latitude);
        double south=Math.max(-90,Math.toDegrees(lat-angle)-1e-9);
        double north=Math.min(90,Math.toDegrees(lat+angle)+1e-9);
        double west=-180,east=180;
        if (south>-90 && north<90) {
            double delta=Math.toDegrees(Math.asin(Math.min(1,Math.sin(angle)/Math.cos(lat))))+1e-9;
            // A date-line crossing uses all longitudes, then the exact distance predicate.
            if (longitude-delta>=-180 && longitude+delta<=180) { west=longitude-delta; east=longitude+delta; }
        }
        return "POLYGON(("+west+" "+south+","+east+" "+south+","+east+" "+north+","+west+" "+north+","+west+" "+south+"))";
    }
}
