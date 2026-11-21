
import { Routes } from '@angular/router';

import { EventCatalog } from './features/events/event-catalog/event-catalog';
import { EventDetail } from './features/events/event-detail/event-detail';
import { BookingForm } from './features/client/booking/booking-form/booking-form';
import { MyBookings } from './features/client/my-bookings/my-bookings';
import { MyTickets } from './features/client/my-tickets/my-tickets';

export const routes: Routes = [
  { path: '', redirectTo: 'events', pathMatch: 'full' },

  { path: 'events', component: EventCatalog },

  { path: 'events/:id', component: EventDetail },

  {
    path: 'client/reservations/new',
    component: BookingForm
  },

  {
    path: 'client/reservations',
    component: MyBookings
  },

  {
    path: 'client/tickets',
    component: MyTickets
  }
];
