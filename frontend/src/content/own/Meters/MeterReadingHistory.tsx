import {
  Box,
  CircularProgress,
  List,
  ListItem,
  ListItemText
} from '@mui/material';
import { useContext } from 'react';
import Meter from '../../../models/owns/meter';
import { useSelector } from '../../../store';
import { CompanySettingsContext } from '../../../contexts/CompanySettingsContext';

interface MeterReadingHistoryProps {
  meter: Meter;
  historyFetched: boolean;
  onRefreshHistogram: () => void;
}

/**
 * The readings of a meter, newest first. P-14 (PPB-35): a reading records a fact and is never changed or deleted, so
 * the history offers no edit or delete action; a correction is recorded as a new reading.
 */
export default function MeterReadingHistory({
  meter,
  historyFetched
}: MeterReadingHistoryProps) {
  const { getFormattedDate } = useContext(CompanySettingsContext);
  const { readingsByMeter, loadingGet } = useSelector(
    (state) => state.readings
  );
  const currentMeterReadings = readingsByMeter[meter?.id] ?? [];

  return (
    <>
      {historyFetched ? (
        loadingGet ? (
          <Box
            display="flex"
            justifyContent="center"
            alignItems="center"
            py={4}
          >
            <CircularProgress />
          </Box>
        ) : (
          <List>
            {[...currentMeterReadings].reverse().map((reading) => (
              <ListItem key={reading.id} divider>
                <ListItemText
                  primary={`${reading.value} ${meter.unit}`}
                  secondary={getFormattedDate(reading.createdAt)}
                />
              </ListItem>
            ))}
          </List>
        )
      ) : null}
    </>
  );
}
