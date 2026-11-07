# Functional Design Document (FDD)
## Hospital-Specific Pharmacy Management System (PMS)

**Version:** 1.0  
**Date:** October 2023  
**Status:** Draft

---

## 1. Introduction & Scope
The Pharmacy Management System (PMS) is a specialized module designed to operate seamlessly within a larger Hospital Management System (HMS) while retaining the ability to function as a standalone application. It manages medicine catalogues, batch-level inventory (enforcing FEFO), OPD/IPD billing, supplier procurement (with OCR-assisted entry), and regulatory compliance reporting.

## 2. User Roles & Personas

| Role | Permissions & Responsibilities |
| :--- | :--- |
| **Super Admin** | Full system access. Manage suppliers, manufacturers, global pricing, user roles, and view all financial reports. |
| **Pharmacy Manager** | Approve purchase orders, authorize stock write-offs/damages, manage sub-location stock transfers, view margin reports. |
| **Pharmacist** | Dispense medicines, verify prescriptions (Schedule H/H1), process patient returns, view FEFO batch details, perform cycle counts. |
| **Cashier** | Create invoices, process payments (Cash/Card/UPI), print receipts. *Cannot* adjust stock, view cost prices, or approve returns. |
| **Nurse/Ward Staff** | (Via HMS Integration) Raise IPD stock requisitions against a patient's UHID (Unique Health ID). |

## 3. Functional Requirements by Module

### 3.1. Master Catalogue Management
* Maintain separate but linked catalogues for **Medicines** (with generic/brand names, dosage, strength, prescription flags) and **Non-Medical Items** (consumables, equipment).
* Manage Supplier and Manufacturer master data, including regulatory license numbers and contact details.

### 3.2. Inventory & Stock Management
* **Batch Tracking**: Every stock entry must be tied to a specific Batch Number, Manufacturing Date, and Expiry Date.
* **FEFO Enforcement**: The system must automatically suggest dispensing from the batch with the nearest expiry date (First Expired, First Out).
* **Multi-Location Support**: Track stock across Main Pharmacy, OPD Counter, Emergency, and ICU.
* **Alerts**: Dashboard widgets for Low Stock (below reorder level) and Near-Expiry (30/60/90 days).

### 3.3. Procurement & Purchase Entry
* **Manual Entry**: Standard form to add stock from a supplier.
* **OCR-Assisted Entry**: Upload an image/PDF of a supplier's physical invoice. The system extracts text (Medicine, Batch, Expiry, Qty, Price) and creates a *draft* purchase entry for the pharmacist to verify and approve.

### 3.4. Billing & Dispensing
* **OPD Billing**: Search patient by Phone or Appointment ID. Fetch prescription via HMS API. Validate prescription requirements. Apply FEFO logic to deduct stock. Generate invoice.
* **IPD Billing**: Fulfill ward requisitions. Post charges to the patient’s central HMS ledger (no immediate cash transaction).
* **Returns**: Process patient returns (restocking if unexpired and sealed, or marking as damaged/expired).

### 3.5. Reporting & Compliance
* **Short Book / Near-Expiry Report**: For timely return to suppliers to minimize financial loss.
* **Schedule H/H1 Register**: Automated audit log of all prescription-only drugs dispensed.
* **Stock Valuation & Margin Report**: Cost Price vs. Selling Price analysis per batch, including supplier discounts.