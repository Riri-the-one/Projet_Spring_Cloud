import { Routes } from '@angular/router';
import { EventCatalog } from './features/events/event-catalog/event-catalog';
import { EventDetail } from './features/events/event-detail/event-detail';
import { organizerGuard } from './core/auth/organizer.guard';
import { OrganizerConsole } from './features/events/organizer-console/organizer-console';

export const routes: Routes = [
  { path: '', redirectTo: 'events', pathMatch: 'full' },
  { path: 'events', component: EventCatalog },
  { path: 'events/:id', component: EventDetail },
  {
    path: 'organizer/events',
    component: OrganizerConsole,
    canActivate: [organizerGuard],
  },
];
