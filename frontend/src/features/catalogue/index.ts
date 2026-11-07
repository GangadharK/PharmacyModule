/**
 * Catalogue feature barrel.
 *
 * ui-context.md §3.2 requires feature pages to be imported "only through the
 * feature barrel, never deep-imported". App.tsx therefore imports from
 * `@/features/catalogue` and knows nothing about the file layout inside it, so
 * moving or splitting a feature folder is not a route change.
 */
export { MedicinesPage } from './medicines/MedicinesPage'
export { MedicineDetail } from './medicines/MedicineDetail'
export { SuppliersPage } from './suppliers/SuppliersPage'
export { SupplierDetail } from './suppliers/SupplierDetail'
export { ManufacturersPage } from './manufacturers/ManufacturersPage'
export { ManufacturerDetail } from './manufacturers/ManufacturerDetail'
export { NonMedicalItemsPage } from './items/NonMedicalItemsPage'
export { NonMedicalItemDetail } from './items/NonMedicalItemDetail'
