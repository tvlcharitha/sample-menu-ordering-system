package com.reonsoftware.possample.db;

import com.reonsoftware.possample.models.DetailedLineItem;
import com.reonsoftware.possample.models.DetailedOrder;
import com.reonsoftware.possample.models.Order;
import com.reonsoftware.possample.models.Tender;
import com.reonsoftware.possample.rest.SettingsController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.BeanPropertySqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * @author Jon Onstott
 * @since 11/1/2015
 */
@Component
public class OrderDao {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("MM/dd/yyyy h:mm a");
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderDao.class);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    private ItemDao itemDao;

    public List<DetailedOrder> getOrders(OrderFilter filter) {
        String filterSql = filter.getFilterSql();
        String orderSql = "SELECT orders.*, tender.amount_tendered, tender.change_due " +
                "FROM orders " +
                "LEFT OUTER JOIN tender ON orders.order_id = tender.order_id " +
                (!filterSql.isEmpty() ? " WHERE " + filterSql : "");
        return jdbcTemplate.query(orderSql, (orderRs, rowNum) -> {
            long orderId = orderRs.getLong("order_id");

            Integer orderNumber = orderRs.getInt("order_number");
            orderNumber = orderRs.wasNull() ? null : orderNumber;

            Date numberAssignDate = orderRs.getTimestamp("number_assign_date");
            String formattedDate = numberAssignDate != null ? DATE_FORMAT.format(numberAssignDate) : null;

            List<DetailedLineItem> lineItems = itemDao.getDetailedLineItems(orderId);

            BigDecimal amountTendered = orderRs.getBigDecimal("tender.amount_tendered");
            BigDecimal changeDue = orderRs.getBigDecimal("tender.change_due");
            Tender tender = amountTendered != null && changeDue != null
                    ? new Tender(null, orderId, amountTendered, changeDue)
                    : null;

            BigDecimal totalDue = calculateTotalDue(lineItems);

            return new DetailedOrder(orderId, orderNumber, formattedDate, lineItems, totalDue, tender);
        });
    }

    /**
     * @return the price of all of the items, including sales tax, or null if there aren't any items
     */
    private BigDecimal calculateTotalDue(List<DetailedLineItem> lineItems) {
        if (lineItems.isEmpty())
            return null;

        BigDecimal total = new BigDecimal(0);
        for (DetailedLineItem lineItem : lineItems) {
            total = total.add(lineItem.getExtendedPrice());
        }

        BigDecimal tax = total.multiply(new BigDecimal(SettingsController.SALES_TAX_RATE));
        return total.add(tax);
    }

    public long createOrder(Order order) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        namedParameterJdbcTemplate.update("INSERT INTO orders(order_number) VALUES (:orderNumber)", new BeanPropertySqlParameterSource(order), keyHolder);
        LOGGER.info("Created a new Order: " + order);
        return keyHolder.getKey().longValue();
    }

    public int assignOrderNumber(long orderId) {
        Integer existingOrderNumber = jdbcTemplate.queryForObject("SELECT order_number FROM orders WHERE order_id = ?", Integer.class, orderId);
        if (existingOrderNumber != null)
            return existingOrderNumber;

        Integer newOrderNumber = determineNewOrderNumber();
        jdbcTemplate.update("UPDATE orders SET order_number = ?, number_assign_date = CURRENT_TIMESTAMP WHERE order_id = ?", newOrderNumber, orderId);
        LOGGER.info("Assigned order number " + newOrderNumber + " to order " + orderId);
        return newOrderNumber;
    }

    /**
     * @return the most recently assigned order number, or 0 if there isn't one
     */
    private int lookupMostRecentOrderNumber() {
        String mostRecentOrderNumberAssignDateSql = "SELECT MAX(B.number_assign_date) FROM orders AS B WHERE B.order_number IS NOT NULL";
        try {
            return jdbcTemplate.queryForObject("SELECT order_number FROM orders AS A WHERE A.number_assign_date = (" + mostRecentOrderNumberAssignDateSql + ")", Integer.class);
        } catch (EmptyResultDataAccessException e) {
            // TODO: it would be nice to not have to rely on exceptions in normal operation
            return 0;
        }
    }

    /**
     * @return the new order number, resetting after we've reached 100
     */
    private Integer determineNewOrderNumber() {
        int incrementedValue = lookupMostRecentOrderNumber() + 1;
        return (incrementedValue > 100 ? 1 : incrementedValue);
    }

    public void addItemToOrder(long orderId, long itemId) {
        Integer existingQuantity = countQuantityOfItemForOrder(orderId, itemId);
        if (existingQuantity == 0)
            createOrderLineItem(orderId, itemId);
        else
            updateOrderLineItem(orderId, itemId, existingQuantity + 1);
        LOGGER.info("Added item " + itemId + " to order " + orderId);
    }

    public void updateItemQuantity(long orderId, long itemId, int quantity) {
        jdbcTemplate.update("UPDATE order_line_items SET quantity = ? WHERE order_id = ? AND item_id = ?", quantity, orderId, itemId);
    }

    /**
     * Removes all occurrences of the item from the order
     */
    public void deleteItemFromOrder(long orderId, long itemId) {
        jdbcTemplate.update("DELETE FROM order_line_items WHERE order_id = ? AND item_id = ?", orderId, itemId);
        LOGGER.info("Deleted all items with ID " + itemId + " from order " + orderId);
    }

    private int countQuantityOfItemForOrder(long orderId, long itemId) {
        try {
            return jdbcTemplate.queryForObject("SELECT quantity FROM order_line_items WHERE order_id = ? AND item_id = ?", Integer.class, orderId, itemId);
        } catch (EmptyResultDataAccessException e) {
            // TODO: it would be nice if catching exceptions wasn't part of normal operation...
            return 0;
        }
    }

    private void createOrderLineItem(long orderId, long itemId) {
        jdbcTemplate.update("INSERT INTO order_line_items(order_id, item_id, quantity) VALUES (?,?,1)", orderId, itemId);
    }

    private void updateOrderLineItem(long orderId, long itemId, int newQuantity) {
        jdbcTemplate.update("UPDATE order_line_items SET quantity = ? WHERE order_id = ? AND item_id = ?", newQuantity, orderId, itemId);
    }

}
