import { FieldType } from '../../../models/owns/fieldConfiguration';
import { WorkOrderRequestConfiguration } from '../../../models/owns/workOrderRequestConfiguration';

/**
 * Returns the configured type of a request field.
 * A missing configuration is treated as OPTIONAL.
 */
export const getRequestFieldType = (
  config: WorkOrderRequestConfiguration | undefined,
  fieldName: string
): FieldType =>
  config?.fieldConfigurations?.find((fc) => fc.fieldName === fieldName)
    ?.fieldType ?? FieldType.OPTIONAL;

export const isRequestFieldHidden = (
  config: WorkOrderRequestConfiguration | undefined,
  fieldName: string
): boolean => getRequestFieldType(config, fieldName) === FieldType.HIDDEN;
