# Payment Optimization

A Java 21 application that selects the optimal payment method for orders, maximizing discounts and minimizing card usage, according to the specification.

## Requirements

- Maven 3.9.0 or higher
- Java 21

## Building the Application

1. Clone the repository or download the source code as a ZIP and extract it.
2. In the root directory, run:

    ```bash
    mvn clean package
    ```

3. The resulting `.jar` file will be located in the `target/` directory with the name:

    ```
    app.jar
    ```

## Running the Application

To run the program, use the following command in the project’s root directory:

```bash
java -jar target/app.jar data/orders.json data/paymentmethods.json
