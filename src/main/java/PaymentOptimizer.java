import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

public class PaymentOptimizer {

    private static final String POINTS_METHOD = "PUNKTY";
    private static final String MZYSK_METHOD = "mZysk";
    private static final String BOSBANKRUT_METHOD = "BosBankrut";
    private static final BigDecimal MAX_COST = new BigDecimal("999999999");
    private static final double POINTS_CARD_COMBO_DISCOUNT = 0.10;
    private static final BigDecimal MIN_POINTS_USAGE = new BigDecimal("15.00");

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Order {
        @JsonProperty("id")
        public String id;

        @JsonProperty("value")
        public BigDecimal value;

        @JsonProperty("promotions")
        public List<String> promotions;
    }

    static class PaymentMethod {
        public String id;
        public int discount;
        public BigDecimal limit;
    }

    static class Usage {
        private BigDecimal amount = BigDecimal.ZERO;

        public void add(BigDecimal value) {
            amount = amount.add(value);
        }

        public BigDecimal getAmount() {
            return amount.setScale(2, RoundingMode.HALF_UP);
        }
    }

    static class Assignment {
        public final String orderId;
        public final String paymentMethod;
        public final BigDecimal pointsUsed;
        private final BigDecimal cardUsed;
        public final BigDecimal cost;

        Assignment(String orderId, String paymentMethod, BigDecimal pointsUsed, BigDecimal cardUsed, BigDecimal cost) {
            this.orderId = orderId;
            this.paymentMethod = paymentMethod;
            this.pointsUsed = pointsUsed;
            this.cardUsed = cardUsed;
            this.cost = cost;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java -jar app.jar <orders.json> <paymentmethods.json>");
            return;
        }

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        List<Order> orders = loadOrders(args[0], mapper);
        List<PaymentMethod> paymentMethods = loadPaymentMethods(args[1], mapper);

        System.out.println("Loaded orders: " + orders.size());
        System.out.println("Loaded payment methods: " + paymentMethods.size());

        Map<String, PaymentMethod> paymentMap = createPaymentMethodMap(paymentMethods);
        Map<String, Usage> usageMap = initializeUsageMap(paymentMethods);
        List<Assignment> bestAssignments = findOptimalAssignments(orders, paymentMap);

        processAndPrintResults(orders, bestAssignments, usageMap);
    }

    public static List<Order> loadOrders(String filePath, ObjectMapper mapper) throws Exception {
        return mapper.readValue(new File(filePath), new TypeReference<>() {});
    }

    protected static List<PaymentMethod> loadPaymentMethods(String filePath, ObjectMapper mapper) throws Exception {
        return mapper.readValue(new File(filePath), new TypeReference<>() {});
    }

    public static Map<String, PaymentMethod> createPaymentMethodMap(List<PaymentMethod> paymentMethods) {
        return paymentMethods.stream().collect(Collectors.toMap(p -> p.id, p -> p));
    }

    public static Map<String, Usage> initializeUsageMap(List<PaymentMethod> paymentMethods) {
        Map<String, Usage> usageMap = new HashMap<>();
        paymentMethods.forEach(p -> usageMap.put(p.id, new Usage()));
        return usageMap;
    }

    public static void processAndPrintResults(List<Order> orders, List<Assignment> bestAssignments, Map<String, Usage> usageMap) {
        if (bestAssignments.isEmpty()) {
            System.out.println("No optimal assignments found.");
            return;
        }

        for (Assignment assignment : bestAssignments) {
            Order order = findOrderById(orders, assignment.orderId);
            printOrderProcessingInfo(order, assignment);

            updateUsageMap(usageMap, assignment);
        }

        printUsageSummary(usageMap);
    }

    public static Order findOrderById(List<Order> orders, String orderId) {
        return orders.stream()
                .filter(o -> o.id.equals(orderId))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
    }

    private static void printOrderProcessingInfo(Order order, Assignment assignment) {
        System.out.println("Processing order: " + order.id + ", Value: " + order.value);

        if (assignment.pointsUsed.compareTo(BigDecimal.ZERO) > 0) {
            System.out.println("Selected payment method for " + assignment.orderId +
                    ": " + POINTS_METHOD + " Amount: " + assignment.pointsUsed);
        }

        if (assignment.cardUsed.compareTo(BigDecimal.ZERO) > 0) {
            System.out.println("Selected payment method for " + assignment.orderId +
                    ": " + assignment.paymentMethod + " Amount: " + assignment.cardUsed);
        }
    }

    public static void updateUsageMap(Map<String, Usage> usageMap, Assignment assignment) {
        if (assignment.pointsUsed.compareTo(BigDecimal.ZERO) > 0) {
            usageMap.get(POINTS_METHOD).add(assignment.pointsUsed);
        }
        if (assignment.cardUsed.compareTo(BigDecimal.ZERO) > 0) {
            usageMap.get(assignment.paymentMethod).add(assignment.cardUsed);
        }
    }

    public static List<Assignment> findOptimalAssignments(List<Order> orders, Map<String, PaymentMethod> paymentMap) {
        List<Assignment> bestAssignments = new ArrayList<>();
        BigDecimal bestTotalCost = MAX_COST;

        List<List<Assignment>> allCombinations = generateCombinations(orders, paymentMap);
        System.out.println("Found " + allCombinations.size() + " possible combinations.");

        for (List<Assignment> combination : allCombinations) {
            CombinationEvaluationResult result = evaluateCombination(combination, paymentMap);

            if (result.isValid() && (bestAssignments.isEmpty() || compareCombinations(combination, bestAssignments, paymentMap) < 0)) {
                bestTotalCost = result.getTotalCost();
                bestAssignments = new ArrayList<>(combination);
            }
        }

        return bestAssignments;
    }

    public static CombinationEvaluationResult evaluateCombination(List<Assignment> combination,
                                                                  Map<String, PaymentMethod> paymentMap) {
        Map<String, BigDecimal> remainingLimits = paymentMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().limit));

        BigDecimal totalCost = BigDecimal.ZERO;
        boolean valid = true;

        for (Assignment assignment : combination) {
            totalCost = totalCost.add(assignment.cost);

            BigDecimal pointsLimit = remainingLimits.getOrDefault(POINTS_METHOD, BigDecimal.ZERO);
            BigDecimal cardLimit = remainingLimits.getOrDefault(assignment.paymentMethod, BigDecimal.ZERO);

            if (assignment.pointsUsed.compareTo(pointsLimit) > 0 ||
                    assignment.cardUsed.compareTo(cardLimit) > 0) {
                valid = false;
                break;
            }

            remainingLimits.put(POINTS_METHOD, pointsLimit.subtract(assignment.pointsUsed));
            if (!assignment.paymentMethod.equals(POINTS_METHOD)) {
                remainingLimits.put(assignment.paymentMethod, cardLimit.subtract(assignment.cardUsed));
            }
        }

        return new CombinationEvaluationResult(valid, totalCost);
    }

    public static int compareCombinations(List<Assignment> a, List<Assignment> b,
                                             Map<String, PaymentMethod> paymentMap) {
        BigDecimal aPointsUsage = calculateTotalPointsUsage(a);
        BigDecimal bPointsUsage = calculateTotalPointsUsage(b);
        BigDecimal pointsLimit = paymentMap.get(POINTS_METHOD).limit;

        // Combinations that respect the points limit
        boolean aValid = aPointsUsage.compareTo(pointsLimit) <= 0;
        boolean bValid = bPointsUsage.compareTo(pointsLimit) <= 0;

        if (aValid && !bValid) return -1;
        if (!aValid && bValid) return 1;

        // Total costs comparison
        BigDecimal aCost = calculateTotalCost(a);
        BigDecimal bCost = calculateTotalCost(b);
        return aCost.compareTo(bCost);
    }

    public static BigDecimal calculateTotalPointsUsage(List<Assignment> assignments) {
        return assignments.stream()
                .map(ass -> ass.pointsUsed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal calculateTotalCost(List<Assignment> assignments) {
        return assignments.stream()
                .map(ass -> ass.cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static List<List<Assignment>> generateCombinations(List<Order> orders, Map<String, PaymentMethod> paymentMap) {
        List<List<Assignment>> result = new ArrayList<>();
        generateCombinationsRecursive(orders, paymentMap, 0, new ArrayList<>(), result);
        return result;
    }

    private static void generateCombinationsRecursive(List<Order> orders, Map<String, PaymentMethod> paymentMap,
                                                      int index, List<Assignment> current, List<List<Assignment>> result) {
        if (index == orders.size()) {
            result.add(new ArrayList<>(current));
            return;
        }

        Order order = orders.get(index);
        PaymentMethod pointsMethod = paymentMap.get(POINTS_METHOD);

        payWithPointsIfPossible(orders, paymentMap, index, current, result, pointsMethod, order);

        payWithCardsIfAvaible(orders, paymentMap, index, current, result, order);

        payWithOtherMethod(orders, paymentMap, index, current, result, order, pointsMethod);
    }

    private static void payWithOtherMethod(List<Order> orders, Map<String, PaymentMethod> paymentMap, int index, List<Assignment> current, List<List<Assignment>> result, Order order, PaymentMethod pointsMethod) {
        if (order.promotions == null) {
            BigDecimal usedPoints = calculateTotalPointsUsage(current);
            BigDecimal remainingPoints = pointsMethod.limit.subtract(usedPoints);

            BigDecimal targetCost = order.value.multiply(BigDecimal.valueOf(1 - POINTS_CARD_COMBO_DISCOUNT));
            BigDecimal pointsToUse = usedPoints.compareTo(BigDecimal.ZERO) == 0
                    ? MIN_POINTS_USAGE
                    : remainingPoints.min(targetCost);

            if (pointsToUse.compareTo(BigDecimal.ZERO) > 0 && pointsToUse.compareTo(remainingPoints) <= 0) {
                PaymentMethod mZyskMethod = paymentMap.get(MZYSK_METHOD);
                BigDecimal cardToUse = targetCost.subtract(pointsToUse);

                BigDecimal remainingValue = cardToUse.divide(
                        BigDecimal.valueOf(1 - POINTS_CARD_COMBO_DISCOUNT), 2, RoundingMode.HALF_UP);

                if (cardToUse.compareTo(BigDecimal.ZERO) >= 0 &&
                        mZyskMethod.limit.compareTo(remainingValue) >= 0) {
                    current.add(new Assignment(order.id, MZYSK_METHOD, pointsToUse, cardToUse, targetCost));
                    generateCombinationsRecursive(orders, paymentMap, index + 1, current, result);
                    current.remove(current.size() - 1);
                }
            }
        }
    }

    private static void payWithCardsIfAvaible(List<Order> orders, Map<String, PaymentMethod> paymentMap, int index, List<Assignment> current, List<List<Assignment>> result, Order order) {
        if (order.promotions != null) {
            for (String promo : order.promotions) {
                PaymentMethod method = paymentMap.get(promo);
                if (method != null && method.limit.compareTo(order.value) >= 0) {
                    BigDecimal cost = calculateDiscountedCost(order.value, method.discount);
                    current.add(new Assignment(order.id, method.id, BigDecimal.ZERO, cost, cost));
                    generateCombinationsRecursive(orders, paymentMap, index + 1, current, result);
                    current.remove(current.size() - 1);
                }
            }
        }
    }

    private static void payWithPointsIfPossible(List<Order> orders, Map<String, PaymentMethod> paymentMap, int index, List<Assignment> current, List<List<Assignment>> result, PaymentMethod pointsMethod, Order order) {
        if (pointsMethod.limit.compareTo(order.value) >= 0) {
            BigDecimal cost = calculateDiscountedCost(order.value, pointsMethod.discount);
            current.add(new Assignment(order.id, POINTS_METHOD, cost, BigDecimal.ZERO, cost));
            generateCombinationsRecursive(orders, paymentMap, index + 1, current, result);
            current.remove(current.size() - 1);
        }
    }

    public static BigDecimal calculateDiscountedCost(BigDecimal value, int discount) {
        return value.multiply(BigDecimal.valueOf(1 - discount / 100.0))
                .setScale(2, RoundingMode.HALF_UP);
    }

    public static void printUsageSummary(Map<String, Usage> usageMap) {
        List<String> orderedMethods = Arrays.asList(MZYSK_METHOD, BOSBANKRUT_METHOD, POINTS_METHOD);
        orderedMethods.forEach(method -> {
            Usage usage = usageMap.get(method);
            if (usage != null && usage.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                System.out.println(method + " " + usage.getAmount());
            }
        });
    }

    public static class CombinationEvaluationResult {
        private final boolean valid;
        private final BigDecimal totalCost;

        public CombinationEvaluationResult(boolean valid, BigDecimal totalCost) {
            this.valid = valid;
            this.totalCost = totalCost;
        }

        public boolean isValid() {
            return valid;
        }

        public BigDecimal getTotalCost() {
            return totalCost;
        }
    }
}