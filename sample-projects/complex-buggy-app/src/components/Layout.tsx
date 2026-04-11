import React from 'react';
import { Header } from './Header';
import { Sidebar } from './Sidebar';
import { Footer } from './Footer';

// Bug: Unused props
export const Layout = ({ children }: { children: React.ReactNode }) => {
  return (
    <div className="layout">
      <Header />
      <Sidebar />
      <main>{children}</main>
      <Footer />
    </div>
  );
};