package com.werewolf.repo;

import com.werewolf.model.ItemDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ItemDefinitionRepository extends JpaRepository<ItemDefinition, Long> {
    List<ItemDefinition> findByEnabledTrueOrderByTypeAscPriceAsc();
    Optional<ItemDefinition> findByCode(String code);
    List<ItemDefinition> findByType(String type);
}
