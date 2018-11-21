import React from 'react';
import { shallow, mount, render } from 'enzyme';

import { HistoryRow } from '../PaymentHistory';
import { convertTimestampToDate } from '../../../helpers';

it('renders history row with props', () => {
  const props  = {
    item: {
      id: 'id1',
      product: {
        price: {
          amount: 0
        },
        presentation: {
          priceLabel:"FreePrice",
          productLabel: "FreeLabel"
        },
      },
      refund: {
        id: 'rid1',
        timestamp: 1542802197874
      },
      timestamp: 1542802197874
    },
    refundPurchase: () => {}
  };
  
  const row = shallow((
    <HistoryRow {...props}/>
  ));
  const date1 = convertTimestampToDate(props.item.timestamp);
  const date2 = convertTimestampToDate(props.item.refund.timestamp);
  expect(row.contains(<td>FreePrice</td>)).toEqual(true);
  expect(row.contains(<td>FreeLabel</td>)).toEqual(true);
  expect(row.contains(<td>{date1}</td>)).toEqual(true);
  expect(row.contains(<td>{date2}</td>)).toEqual(true);
});
