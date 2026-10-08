package com.vesanieminen.electricitydashboard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.vesanieminen.froniusvisualizer.components.BatterySimulationSection.parseCapacities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class BatterySimulationSectionTest {

    @Test
    void parsesCapacityLists() {
        assertEquals(List.of(5d, 10d, 15d), parseCapacities("5, 10, 15"));
        assertEquals(List.of(5d, 10d, 15d), parseCapacities("15 5;10 10"));
        assertEquals(List.of(7.5, 10d), parseCapacities("7,5, 10"));
        assertEquals(List.of(7.5, 10d), parseCapacities("7.5 10"));
        assertEquals(List.of(5d, 10d, 20d), parseCapacities("5,10,20"));
        assertEquals(List.of(), parseCapacities(" "));
        assertThrows(NumberFormatException.class, () -> parseCapacities("5, ten"));
    }
}
