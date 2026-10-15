package smartticketing.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import smartticketing.entity.TheaterCollectionProgress;

public interface TheaterCollectionProgressRepository extends JpaRepository<TheaterCollectionProgress, String> {}
