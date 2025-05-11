import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class PaymentOptimizerTest {

    private ObjectMapper mapper;
    private List<PaymentOptimizer.Order> orders;
    private List<PaymentOptimizer.PaymentMethod> paymentMethods;
    private Map<String, PaymentOptimizer.PaymentMethod> paymentMap;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        orders = new ArrayList<>();
        paymentMethods = new ArrayList<>();
        paymentMap = new HashMap<>();

        // Setup test data
        PaymentOptimizer.Order order = new PaymentOptimizer.Order();
        order.id = "1";
        order.value = new BigDecimal("100.00");
        order.promotions = Arrays.asList("mZysk", "BosBankrut");
        orders.add(order);

        PaymentOptimizer.PaymentMethod pointsMethod = new PaymentOptimizer.PaymentMethod();
        pointsMethod.id = "PUNKTY";
        pointsMethod.discount = 0;
        pointsMethod.limit = new BigDecimal("50.00");
        paymentMethods.add(pointsMethod);

        PaymentOptimizer.PaymentMethod mZyskMethod = new PaymentOptimizer.PaymentMethod();
        mZyskMethod.id = "mZysk";
        mZyskMethod.discount = 10;
        mZyskMethod.limit = new BigDecimal("1000.00");
        paymentMethods.add(mZyskMethod);

        PaymentOptimizer.PaymentMethod bosBankrutMethod = new PaymentOptimizer.PaymentMethod();
        bosBankrutMethod.id = "BosBankrut";
        bosBankrutMethod.discount = 5;
        bosBankrutMethod.limit = new BigDecimal("500.00");
        paymentMethods.add(bosBankrutMethod);

        paymentMap.put("PUNKTY", pointsMethod);
        paymentMap.put("mZysk", mZyskMethod);
        paymentMap.put("BosBankrut", bosBankrutMethod);
    }

    @Test
    void testLoadOrders() throws Exception {
        String json = "[{\"id\":\"1\",\"value\":100.00,\"promotions\":[\"mZysk\"]}]";
        File tempFile = File.createTempFile("orders", ".json");
        Files.write(tempFile.toPath(), json.getBytes());

        List<PaymentOptimizer.Order> loadedOrders = PaymentOptimizer.loadOrders(tempFile.getPath(), mapper);
        assertEquals(1, loadedOrders.size());
        assertEquals("1", loadedOrders.get(0).id);
        assertEquals(new BigDecimal("100.00"), loadedOrders.get(0).value);
        assertEquals(Arrays.asList("mZysk"), loadedOrders.get(0).promotions);
    }

    @Test
    void testLoadOrdersInvalidFile() {
        assertThrows(Exception.class, () -> PaymentOptimizer.loadOrders("nonexistent.json", mapper),
                "Should throw exception for invalid file");
    }

    @Test
    void testLoadOrdersEmptyJson() throws Exception {
        String json = "[]";
        File tempFile = File.createTempFile("orders", ".json");
        Files.write(tempFile.toPath(), json.getBytes());

        List<PaymentOptimizer.Order> loadedOrders = PaymentOptimizer.loadOrders(tempFile.getPath(), mapper);
        assertTrue(loadedOrders.isEmpty(), "Should return empty list for empty JSON");
    }

    @Test
    void testLoadPaymentMethods() throws Exception {
        String json = "[{\"id\":\"mZysk\",\"discount\":10,\"limit\":1000.00}]";
        File tempFile = File.createTempFile("paymentmethods", ".json");
        Files.write(tempFile.toPath(), json.getBytes());

        List<PaymentOptimizer.PaymentMethod> loadedMethods = PaymentOptimizer.loadPaymentMethods(tempFile.getPath(), mapper);
        assertEquals(1, loadedMethods.size());
        assertEquals("mZysk", loadedMethods.get(0).id);
        assertEquals(10, loadedMethods.get(0).discount);
        assertEquals(new BigDecimal("1000.00"), loadedMethods.get(0).limit);
    }

    @Test
    void testLoadPaymentMethodsInvalidJson() {
        String json = "[{invalid}]";
        File tempFile = null;
        tempFile = new File(tempFile, "paymentmethods.json");
        try {
            Files.write(tempFile.toPath(), json.getBytes());
            File finalTempFile = tempFile;
            assertThrows(Exception.class, () -> PaymentOptimizer.loadPaymentMethods(finalTempFile.getPath(), mapper),
                    "Should throw exception for invalid JSON");
        } catch (Exception e) {
            fail("Failed to write temp file: " + e.getMessage());
        }
    }

    @Test
    void testCreatePaymentMethodMap() {
        Map<String, PaymentOptimizer.PaymentMethod> result = PaymentOptimizer.createPaymentMethodMap(paymentMethods);
        assertEquals(3, result.size());
        assertTrue(result.containsKey("PUNKTY"));
        assertTrue(result.containsKey("mZysk"));
        assertTrue(result.containsKey("BosBankrut"));
        assertEquals(10, result.get("mZysk").discount);
    }

    @Test
    void testCreatePaymentMethodMapEmptyList() {
        Map<String, PaymentOptimizer.PaymentMethod> result = PaymentOptimizer.createPaymentMethodMap(new ArrayList<>());
        assertTrue(result.isEmpty(), "Should return empty map for empty payment methods list");
    }

    @Test
    void testInitializeUsageMap() {
        Map<String, PaymentOptimizer.Usage> usageMap = PaymentOptimizer.initializeUsageMap(paymentMethods);
        assertEquals(3, usageMap.size());
        assertTrue(usageMap.containsKey("PUNKTY"));
        assertTrue(usageMap.containsKey("mZysk"));
        assertTrue(usageMap.containsKey("BosBankrut"));
        assertEquals(BigDecimal.ZERO.setScale(2), usageMap.get("PUNKTY").getAmount());
    }

    @Test
    void testFindOptimalAssignments() {
        List<PaymentOptimizer.Assignment> assignments = PaymentOptimizer.findOptimalAssignments(orders, paymentMap);
        assertFalse(assignments.isEmpty());
        assertEquals("1", assignments.get(0).orderId);
        assertTrue(assignments.get(0).cost.compareTo(new BigDecimal("100.00")) <= 0);
    }

    @Test
    void testFindOptimalAssignmentsNoValidCombinations() {
        List<PaymentOptimizer.Order> noPromoOrders = new ArrayList<>();
        PaymentOptimizer.Order order = new PaymentOptimizer.Order();
        order.id = "2";
        order.value = new BigDecimal("1100.00"); // Exceeds mZysk limit after points + card combo
        order.promotions = null;
        noPromoOrders.add(order);

        List<PaymentOptimizer.Assignment> assignments = PaymentOptimizer.findOptimalAssignments(noPromoOrders, paymentMap);
        assertTrue(assignments.isEmpty(), "Should return empty list when no valid combinations exist");
    }

    @Test
    void testEvaluateCombination() {
        List<PaymentOptimizer.Assignment> combination = new ArrayList<>();
        combination.add(new PaymentOptimizer.Assignment("1", "mZysk",
                BigDecimal.ZERO, new BigDecimal("90.00"), new BigDecimal("90.00")));

        PaymentOptimizer.CombinationEvaluationResult result = PaymentOptimizer.evaluateCombination(combination, paymentMap);
        assertTrue(result.isValid());
        assertEquals(new BigDecimal("90.00"), result.getTotalCost());
    }

    @Test
    void testEvaluateCombinationExceedsLimit() {
        List<PaymentOptimizer.Assignment> combination = new ArrayList<>();
        combination.add(new PaymentOptimizer.Assignment("1", "PUNKTY",
                new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"))); // Exceeds PUNKTY limit (50.00)

        PaymentOptimizer.CombinationEvaluationResult result = PaymentOptimizer.evaluateCombination(combination, paymentMap);
        assertFalse(result.isValid(), "Should be invalid when exceeding payment method limit");
    }

    @Test
    void testCalculateDiscountedCost() {
        BigDecimal value = new BigDecimal("100.00");
        int discount = 10;
        BigDecimal result = PaymentOptimizer.calculateDiscountedCost(value, discount);
        assertEquals(new BigDecimal("90.00"), result, "Discounted cost should be calculated with scale 2");
    }

    @Test
    void testCalculateDiscountedCostZeroDiscount() {
        BigDecimal value = new BigDecimal("100.00");
        int discount = 0;
        BigDecimal result = PaymentOptimizer.calculateDiscountedCost(value, discount);
        assertEquals(new BigDecimal("100.00"), result, "Should return original value for zero discount");
    }

    @Test
    void testCalculateDiscountedCostNegativeValue() {
        BigDecimal value = new BigDecimal("-100.00");
        int discount = 10;
        BigDecimal result = PaymentOptimizer.calculateDiscountedCost(value, discount);
        assertEquals(new BigDecimal("-90.00"), result, "Should handle negative values correctly");
    }

    @Test
    void testFindOrderById() {
        PaymentOptimizer.Order result = PaymentOptimizer.findOrderById(orders, "1");
        assertNotNull(result);
        assertEquals("1", result.id);

        assertThrows(RuntimeException.class, () -> PaymentOptimizer.findOrderById(orders, "2"));
    }

    @Test
    void testUpdateUsageMap() {
        Map<String, PaymentOptimizer.Usage> usageMap = PaymentOptimizer.initializeUsageMap(paymentMethods);
        PaymentOptimizer.Assignment assignment = new PaymentOptimizer.Assignment(
                "1", "mZysk", new BigDecimal("10.00"), new BigDecimal("90.00"), new BigDecimal("100.00"));

        PaymentOptimizer.updateUsageMap(usageMap, assignment);
        assertEquals(new BigDecimal("10.00"), usageMap.get("PUNKTY").getAmount());
        assertEquals(new BigDecimal("90.00"), usageMap.get("mZysk").getAmount());
        assertEquals(BigDecimal.ZERO.setScale(2), usageMap.get("BosBankrut").getAmount());
    }

    @Test
    void testGenerateCombinations() {
        List<List<PaymentOptimizer.Assignment>> combinations = PaymentOptimizer.generateCombinations(orders, paymentMap);
        assertFalse(combinations.isEmpty());
        assertTrue(combinations.stream().allMatch(c -> c.size() == 1));
        assertTrue(combinations.stream().anyMatch(c -> c.get(0).paymentMethod.equals("mZysk")));
        assertTrue(combinations.stream().anyMatch(c -> c.get(0).paymentMethod.equals("BosBankrut")));
    }

    @Test
    void testGenerateCombinationsNoPromotions() {
        List<PaymentOptimizer.Order> noPromoOrders = new ArrayList<>();
        PaymentOptimizer.Order order = new PaymentOptimizer.Order();
        order.id = "2";
        order.value = new BigDecimal("20.00");
        order.promotions = null;
        noPromoOrders.add(order);

        List<List<PaymentOptimizer.Assignment>> combinations = PaymentOptimizer.generateCombinations(noPromoOrders, paymentMap);
        assertFalse(combinations.isEmpty(), "Should generate combinations for orders without promotions");
        assertTrue(combinations.stream().anyMatch(c -> c.get(0).paymentMethod.equals("mZysk") &&
                        c.get(0).pointsUsed.compareTo(BigDecimal.ZERO) > 0),
                "Should include points + mZysk combination");
    }

    @Test
    void testCompareCombinations() {
        List<PaymentOptimizer.Assignment> combo1 = new ArrayList<>();
        combo1.add(new PaymentOptimizer.Assignment("1", "mZysk", BigDecimal.ZERO, new BigDecimal("90.00"), new BigDecimal("90.00")));

        List<PaymentOptimizer.Assignment> combo2 = new ArrayList<>();
        combo2.add(new PaymentOptimizer.Assignment("1", "BosBankrut", BigDecimal.ZERO, new BigDecimal("95.00"), new BigDecimal("95.00")));

        int result = PaymentOptimizer.compareCombinations(combo1, combo2, paymentMap);
        assertTrue(result < 0, "Combo1 should be preferred due to lower cost");

        // Test with points exceeding limit
        List<PaymentOptimizer.Assignment> combo3 = new ArrayList<>();
        combo3.add(new PaymentOptimizer.Assignment("1", "PUNKTY", new BigDecimal("60.00"), BigDecimal.ZERO, new BigDecimal("60.00")));

        result = PaymentOptimizer.compareCombinations(combo1, combo3, paymentMap);
        assertTrue(result < 0, "Combo1 should be preferred as combo3 exceeds points limit");
    }

    @Test
    void testCalculateTotalPointsUsage() {
        List<PaymentOptimizer.Assignment> assignments = new ArrayList<>();
        assignments.add(new PaymentOptimizer.Assignment("1", "mZysk", new BigDecimal("10.00"), new BigDecimal("90.00"), new BigDecimal("100.00")));
        assignments.add(new PaymentOptimizer.Assignment("2", "PUNKTY", new BigDecimal("20.00"), BigDecimal.ZERO, new BigDecimal("20.00")));

        BigDecimal totalPoints = PaymentOptimizer.calculateTotalPointsUsage(assignments);
        assertEquals(new BigDecimal("30.00"), totalPoints, "Should sum all points used");
    }

    @Test
    void testCalculateTotalCost() {
        List<PaymentOptimizer.Assignment> assignments = new ArrayList<>();
        assignments.add(new PaymentOptimizer.Assignment("1", "mZysk", BigDecimal.ZERO, new BigDecimal("90.00"), new BigDecimal("90.00")));
        assignments.add(new PaymentOptimizer.Assignment("2", "BosBankrut", BigDecimal.ZERO, new BigDecimal("95.00"), new BigDecimal("95.00")));

        BigDecimal totalCost = PaymentOptimizer.calculateTotalCost(assignments);
        assertEquals(new BigDecimal("185.00"), totalCost, "Should sum all assignment costs");
    }

    @Test
    void testProcessAndPrintResultsEmptyAssignments() {
        Map<String, PaymentOptimizer.Usage> usageMap = PaymentOptimizer.initializeUsageMap(paymentMethods);
        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        PaymentOptimizer.processAndPrintResults(orders, new ArrayList<>(), usageMap);
        assertTrue(outContent.toString().contains("No optimal assignments found"),
                "Should print message for empty assignments");

        System.setOut(System.out);
    }

    @Test
    void testPrintUsageSummary() {
        Map<String, PaymentOptimizer.Usage> usageMap = PaymentOptimizer.initializeUsageMap(paymentMethods);
        usageMap.get("mZysk").add(new BigDecimal("100.00"));
        usageMap.get("PUNKTY").add(new BigDecimal("20.00"));

        ByteArrayOutputStream outContent = new ByteArrayOutputStream();
        System.setOut(new PrintStream(outContent));

        PaymentOptimizer.printUsageSummary(usageMap);
        String output = outContent.toString();
        assertTrue(output.contains("mZysk 100.00"), "Should print mZysk usage");
        assertTrue(output.contains("PUNKTY 20.00"), "Should print PUNKTY usage");
        assertFalse(output.contains("BosBankrut"), "Should not print zero usage for BosBankrut");

        System.setOut(System.out);
    }
}